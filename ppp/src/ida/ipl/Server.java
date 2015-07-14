package ida.ipl;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import ibis.ipl.ConnectionClosedException;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortConnectUpcall;
import ibis.ipl.SendPort;
import ibis.ipl.SendPortIdentifier;
import ibis.ipl.WriteMessage;

public class Server implements MessageUpcall, ReceivePortConnectUpcall
{

	private final Ida parent;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private final Deque<IbisIdentifier> waitingForWork;
	private ReceivePort receiver;
	private final Deque<Board> deque;
	private final AtomicInteger solutions;
	private Board initialBoard;
	private boolean replyBoards;
	private BoardCache cache;
	private boolean finished;

	public Server(Ida parent)
	{
		this.parent = parent;
		senders = new HashMap<IbisIdentifier, SendPort>();
		waitingForWork = new ArrayDeque<IbisIdentifier>();
		deque = new ArrayDeque<Board>();
		solutions = new AtomicInteger(0);
		finished = false;
	}

	/**
	 * Main function.
	 *
	 * @param arguments
	 *            list of arguments
	 */
	public void run(String fileName, boolean useCache) throws IOException
	{

		initialBoard = null;

		if (fileName == null)
		{
			System.err.println("No input file provided.");
			parent.ibis.registry().terminate();
			System.exit(1);
		}
		else
		{
			try
			{
				initialBoard = new Board(fileName);
			}
			catch (Exception e)
			{
				System.err.println("could not initialize board from file: " + e);
				parent.ibis.registry().terminate();
				System.exit(1);
			}
		}
		if (useCache)
			cache = new BoardCache();
		System.out.println("Running IDA*, initial board:");
		System.out.println(initialBoard);

		// open Ibis ports
		openPorts();

		// solve
		long start = System.currentTimeMillis();
		try
		{
			solve(initialBoard);
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("Solving IDA took " + (end - start) + " milliseconds");
	}

	/**
	 * If a connection to the receive port is established, create a sendport in
	 * the reverse direction.
	 */
	@Override
	public boolean gotConnection(ReceivePort rp, SendPortIdentifier spi)
	{
		try
		{
			IbisIdentifier worker = spi.ibisIdentifier();
			SendPort sender = parent.ibis.createSendPort(Ida.portType);
			sender.connect(worker, "slave");
			senders.put(worker, sender);
			synchronized (waitingForWork)
			{
				waitingForWork.push(worker);
				waitingForWork.notifyAll();
			}
		}
		catch (IOException e)
		{
			e.printStackTrace(System.err);
		}
		return true;
	}

	/**
	 * If a connection to the receive port is lost, close the reverse
	 * connection.
	 */
	@Override
	public void lostConnection(ReceivePort rp, SendPortIdentifier spi, Throwable thrwbl)
	{
		try
		{
			IbisIdentifier worker = spi.ibisIdentifier();
			SendPort sender = senders.get(worker);
			sender.close();
			senders.remove(worker);
		}
		catch (ConnectionClosedException e)
		{
			// do nothing
		}
		catch (IOException e)
		{
			e.printStackTrace(System.err);
		}
	}

	/**
	 * Processes a cube request / notification of found solutions from a worker.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void upcall(ReadMessage rm) throws IOException, ClassNotFoundException
	{
		// Process the incoming message and decrease the number of busy workers
		IbisIdentifier sender = rm.origin().ibisIdentifier();
		int requestValue = 0;
		ArrayList<Board> boards = null;
		boolean recvInt = rm.readBoolean();

		waitingForWork.add(sender);
		if (recvInt)
		{
			requestValue = rm.readInt();
			rm.finish();
			if (requestValue != Ida.INIT_VALUE) //Solution received
			{
				solutions.addAndGet(requestValue);
			}
		}
		else
		{
			boards = (ArrayList<Board>)rm.readObject();
			rm.finish();
			setBoards(boards);
			synchronized (deque)
			{
				if (deque.size() > 1) //senders.size() * 2)
					replyBoards = false;
			}
		}

		waitForQueue();

		// Get the port to the sender and send the cube
		Board replyValue = getBoard(); // may block for some time
		sendBoard(replyValue, sender);

		waitingForWork.remove(sender);
	}

	private Board getBoard()
	{
		Board board = null;
		synchronized (deque)
		{
			if (deque.isEmpty()) //Bound finished and no result found
				return null;
			board = deque.pop();
		}
		return board;
	}

	private void setBoards(ArrayList<Board> boards)
	{
		synchronized (deque)
		{
			for (Board b : boards)
			{
				deque.add(b);
				deque.notify();
			}
		}
	}

	/**
	 * Send a cube to a worker.
	 */
	private void sendBoard(Board board, IbisIdentifier destination) throws IOException
	{
		SendPort port = senders.get(destination);
		WriteMessage wm = port.newMessage();
		wm.writeBoolean(false);
		wm.writeBoolean(replyBoards);
		wm.writeObject(board);
		wm.finish();
	}

	/**
	 * Solves a Rubik's cube by iteratively searching for solutions with a
	 * greater depth. This guarantees the optimal solution is found. Repeats all
	 * work for the previous iteration each iteration though...
	 *
	 * @param cube
	 *            the cube to solve
	 */
	private void solve(Board initialBoard) throws InterruptedException, IOException
	{

		int bound = initialBoard.distance();

		System.out.print("Try bound ");
		System.out.flush();
		initialBoard.setBound(bound);
		synchronized (deque)
		{
			deque.addFirst(initialBoard);
		}

		System.out.print(" " + bound);
		//		synchronized (this)
		//		{
		//			this.wait();
		//		}
		calculateJob();
		shutdown();

		System.out.print("\nresult is " + solutions.get() + " solutions of " + initialBoard.bound() + " steps");
		System.out.flush();
	}

	/**
	 * Creates a receive port to receive cube requests from workers.
	 * 
	 * @throws IOException
	 */
	private void openPorts() throws IOException
	{
		receiver = parent.ibis.createReceivePort(Ida.portType, "server", this, this, new Properties());
		receiver.enableConnections();
		receiver.enableMessageUpcalls();
	}

	/**
	 * Sends a termination message to all connected workers and closes all
	 * ports.
	 * 
	 * @throws IOException
	 */
	private void shutdown() throws IOException
	{
		// Terminate the pool
		parent.ibis.registry().terminate();

		// Close ports (and send termination messages)
		try
		{
			for (SendPort sender : senders.values())
			{
				WriteMessage wm = sender.newMessage();
				wm.writeBoolean(true);
				wm.finish();
				sender.close();
			}
			receiver.close();
		}
		catch (ConnectionClosedException e)
		{
			// do nothing
		}
	}
	//do
	//	{
	//		while (!deque.isEmpty())
	//			deque.remove();
	//
	//		initialBoard.setBound(bound);
	//		deque.addFirst(initialBoard);
	//
	//		System.out.println("Bound " + bound);
	//
	//		while (!deque.isEmpty())
	//		{
	//			while (waitingForWork.isEmpty())
	//			{
	//				System.out.println("Waiting for workers to connect");
	//			}
	//			System.out.println("Workers connected sending board");
	//			sendBoard(getBoard(), waitingForWork.pop());
	//			while (senders.size() > waitingForWork.size() && deque.isEmpty())
	//			{
	//				//While some workers are working - wait for them to finish
	//				System.out.println("Waiting for answer");
	//			}
	//		}
	//
	//		bound++;
	//	} while (solutions.get() == 0);

	private void calculateJob() throws IOException
	{
		if (this.finished)
		{
			System.out.println("I think I'm finished...");
			return;
		}
		Board b = null;
		while ((b = getBoard()) == null)
			waitForQueue();
		if (b.distance() == 1)
			solutions.addAndGet(1);
		else if (b.distance() > b.bound())
			calculateJob();
		else
		{
			ArrayList<Board> boards = cache == null ? b.makeMoves() : b.makeMoves(cache);
			setBoards(boards);
		}
		calculateJob();
	}

	private void waitForQueue()
	{
		synchronized (deque)
		{
			while (deque.isEmpty() && waitingForWork.size() < senders.size())
			{
				try
				{
					deque.wait();
				}
				catch (InterruptedException e)
				{
				}
			}
			if (deque.isEmpty() && waitingForWork.size() == senders.size())
				incrementBound();
		}
	}

	private void incrementBound()
	{
		if (solutions.get() > 0)
		{
			synchronized (this)
			{
				this.notify();
				this.finished = true;
			}
			return;
		}

		synchronized (initialBoard)
		{
			synchronized (deque)
			{
				if (!deque.isEmpty())
					return;
				int bound = initialBoard.bound() + 1;
				initialBoard.setBound(bound);
				System.out.print(" " + bound);
				deque.add(initialBoard);
				replyBoards = true;
			}
		}
	}
}