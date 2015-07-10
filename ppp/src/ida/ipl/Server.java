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

	private enum Status
	{
		INITIALIZING,
		FILLING_DEQUE,
		PROCESSING_DEQUE,
		DEQUE_EMPTY,
		WAITING_FOR_WORKERS,
		DONE
	}

	private final Ida parent;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private final Deque<IbisIdentifier> waitingForWork;
	private ReceivePort receiver;
	private final Deque<Board> deque;
	private Status status;
	private final AtomicInteger busyWorkers;
	private final AtomicInteger solutions;

	Server(Ida parent) throws IOException
	{
		this.parent = parent;
		senders = new HashMap<IbisIdentifier, SendPort>();
		waitingForWork = new ArrayDeque<IbisIdentifier>();
		deque = new ArrayDeque<Board>();
		busyWorkers = new AtomicInteger(0);
		status = Status.INITIALIZING;
		solutions = new AtomicInteger(0);
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
	public void shutdown() throws IOException
	{
		// Terminate the pool
		status = Status.DONE;
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
	 * Waits until all workers have finished their work and sent the number of
	 * solutions.
	 */
	private void waitForWorkers() throws InterruptedException
	{
		synchronized (this)
		{
			status = Status.WAITING_FOR_WORKERS;
			while (busyWorkers.get() != 0)
			{
				this.wait();
			}
		}
	}

	private void waitForWorkersToConnect() throws InterruptedException
	{
		synchronized (waitingForWork)
		{
			while (waitingForWork.isEmpty())
			{
				waitingForWork.wait();
			}
		}
	}

	/**
	 * Get the last cube from the deque, which will have less twists and thus
	 * more work on average than cubes from the start of the deque.
	 */
	private Board getBoard()
	{
		Board board = null;
		try
		{
			synchronized (deque)
			{
				while (status != Status.PROCESSING_DEQUE)
				{
					deque.wait();
				}
				board = deque.pop();
				if (deque.isEmpty())
				{
					status = Status.DEQUE_EMPTY;
				}
			}
		}
		catch (InterruptedException e)
		{
			e.printStackTrace(System.err);
		}
		return board;
	}

	/**
	 * Send a cube to a worker.
	 */
	void sendBoard(Board board, IbisIdentifier destination) throws IOException
	{
		SendPort port = senders.get(destination);
		WriteMessage wm = port.newMessage();
		wm.writeBoolean(false);
		wm.writeObject(board);
		wm.finish();
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

		if (recvInt)
		{
			requestValue = rm.readInt();
			if (requestValue != Ida.INIT_VALUE)
			{
				synchronized (this)
				{
					solutions.addAndGet(requestValue);
					busyWorkers.decrementAndGet();
					waitingForWork.remove(sender);
					this.notify();
				}
			}
		}
		else
		{
			boards = (ArrayList<Board>)rm.readObject();
			synchronized (this)
			{
				for (Board b : boards)
				{
					deque.add(b);
				}
				busyWorkers.decrementAndGet();
				this.notify();
			}
		}

		rm.finish();
		// Get the port to the sender and send the cube
		Board replyValue = getBoard(); // may block for some time
		sendBoard(replyValue, sender);

		// Increase the number of workers we are waiting for
		busyWorkers.incrementAndGet();
		waitingForWork.push(sender);
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

		do
		{
			while (!deque.isEmpty())
				deque.remove();

			status = Status.FILLING_DEQUE;

			initialBoard.setBound(bound);
			deque.addFirst(initialBoard);

			System.out.print(" " + bound);

			while (!deque.isEmpty())
			{
				while (waitingForWork.isEmpty())
					waitForWorkersToConnect();
				sendBoard(getBoard(), waitingForWork.pop());
				while (senders.size() > waitingForWork.size() && deque.isEmpty()) //While some workers are working - wait for them to finish
					waitForWorkersToConnect();
			}

			waitForWorkers();
			bound++;
		} while (solutions.get() == 0);
		shutdown();

		System.out.print("\nresult is " + solutions.get() + " solutions of " + initialBoard.bound() + " steps");
		System.out.flush();
	}

	/**
	 * Main function.
	 *
	 * @param arguments
	 *            list of arguments
	 */
	public void run(String fileName) throws IOException, InterruptedException
	{

		Board initialBoard = null;

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
		System.out.println("Running IDA*, initial board:");
		System.out.println(initialBoard);

		// open Ibis ports
		openPorts();

		// solve
		long start = System.currentTimeMillis();
		solve(initialBoard);
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("Solving cube took " + (end - start) + " milliseconds");
	}
}