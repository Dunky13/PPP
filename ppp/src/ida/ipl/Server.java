package ida.ipl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingDeque;
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

	private final AtomicInteger busyWorkers;
	private BoardCache cache;
	private final LinkedBlockingDeque<Board> deque;
	private Board initialBoard;
	private int minQueueSize;
	private final Ida parent;
	private boolean programFoundSolution;
	private ReceivePort receiver;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private final AtomicInteger solutions;

	public Server(Ida parent) throws IOException
	{
		this.parent = parent;
		senders = new HashMap<IbisIdentifier, SendPort>();
		deque = new LinkedBlockingDeque<Board>();
		busyWorkers = new AtomicInteger(0);
		solutions = new AtomicInteger(0);
		this.programFoundSolution = false;
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

	public void run(String fileName, boolean useCache) throws IOException
	{
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

				initialBoard.setBound(initialBoard.distance());
			}
			catch (Exception e)
			{
				closeIbisDueToError("could not initialize board from file: " + e);
			}
		}
		if (initialBoard == null)
		{
			closeIbisDueToError("could not initialize board from file: " + fileName);
		}

		if (useCache)
			cache = new BoardCache();
		System.out.println("Running IDA*, initial board:");
		System.out.println(initialBoard);

		// open Ibis ports
		openPorts();

		long start = System.currentTimeMillis();
		solveServerSide();
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("Solving IDA took " + (end - start) + " milliseconds");
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
	 * Processes a board request / notification of found solutions from a
	 * worker.
	 */
	@Override
	public void upcall(ReadMessage rm) throws IOException, ClassNotFoundException
	{
		// Process the incoming message and decrease the number of busy workers
		IbisIdentifier sender = rm.origin().ibisIdentifier();
		int requestValue = rm.readInt();
		rm.finish();
		if (requestValue != Ida.INIT_VALUE)
		{
			synchronized (this)
			{
				solutions.addAndGet(requestValue);
				busyWorkers.decrementAndGet();
				this.notify();
			}
		}

		// Get the port to the sender and send the board
		Board replyValue = getBoard(false); // may block for some time
		sendBoard(replyValue, sender);

		// Increase the number of workers we are waiting for
		busyWorkers.incrementAndGet();
	}

	private void closeIbisDueToError(String error)
	{
		System.err.println(error);
		try
		{
			parent.ibis.registry().terminate();
		}
		catch (IOException e)
		{
		}
		System.exit(1);
	}

	private int doEasyTasks()
	{
		int solutions = 0;
		while (deque.size() < this.minQueueSize && !deque.isEmpty())
		{
			Board board = getBoard(true);
			solutions += processBoard(board);
		}
		return solutions;
	}

	private int doHarderTask()
	{
		int solutions = 0;
		while (!deque.isEmpty())
		{
			Board board = getBoard(false);
			solutions += processBoard(board);
		}
		return solutions;
	}

	private Board getBoard(boolean getEasyTask)
	{
		try
		{
			return getEasyTask ? deque.takeFirst() : deque.takeLast();
		}
		catch (InterruptedException e)
		{
		}
		return null;
	}

	private void incrementBound()
	{
		this.programFoundSolution = solutions.get() > 0;
		if (!this.programFoundSolution)
		{
			int bound = initialBoard.bound() + 1;
			initialBoard.setBound(bound);
			System.out.print(" " + bound);
		}
	}

	/**
	 * Creates a receive port to receive board requests from workers.
	 * 
	 * @throws IOException
	 */
	private void openPorts() throws IOException
	{
		receiver = parent.ibis.createReceivePort(Ida.portType, "server", this, this, new Properties());
		receiver.enableConnections();
		receiver.enableMessageUpcalls();
	}

	private int processBoard(Board board)
	{
		if (board == null)
			return 0;
		// If the board is solved, increment the number of found solutions
		if (board.distance() == 1)
			return 1;
		else if (board.distance() > board.bound())
			return 0;
		else
		{
			ArrayList<Board> boards = cache == null ? board.makeMoves() : board.makeMoves(cache);
			if (cache != null)
			{
				for (Board child : boards)
				{
					deque.addFirst(child);
					cache.put(board);
				}
			}
			else
			{
				for (Board child : boards)
				{
					deque.addFirst(child);
				}
			}

			return 0;
		}
	}

	/**
	 * Send a board to a worker.
	 */
	private void sendBoard(Board board, IbisIdentifier destination) throws IOException
	{
		SendPort port = senders.get(destination);
		WriteMessage wm = port.newMessage();
		wm.writeBoolean(false);
		wm.writeObject(board);
		wm.finish();
	}

	/**
	 * Solves a Rubik's board by iteratively searching for solutions with a
	 * greater depth. This guarantees the optimal solution is found. Repeats all
	 * work for the previous iteration each iteration though...
	 *
	 * @param board
	 *            the board to solve
	 */
	private void solveServerSide() throws IOException
	{
		// cache used for board objects. Doing new Board() for every move
		// overloads the garbage collector

		// determine how many boards we should process from the end of the deque
		// in order get it ready for boards

		System.out.print("Bound now:");

		while (!this.programFoundSolution)
		{

			int solution = processBoard(initialBoard);

			minQueueSize = (int)Math.pow(senders.size() * (initialBoard.distance() - 1), 2);

			//Leave the harder tasks for the slaves
			solution += doEasyTasks();

			//This will work on while slaves haven't finished the hard work
			solution += doHarderTask();
			this.solutions.addAndGet(solution);
			waitForWorkers();
			incrementBound();
		}
		shutdown();

		System.out.println();
		System.out.println("Solving board possible in " + solutions + " ways of " + initialBoard.bound() + " steps");
	}

	/**
	 * Waits until all workers have finished their work and sent the number of
	 * solutions.
	 */
	private void waitForWorkers()
	{
		synchronized (this)
		{
			while (busyWorkers.get() != 0)
			{
				try
				{
					this.wait();
				}
				catch (InterruptedException e)
				{
				}
			}
		}
	}
}