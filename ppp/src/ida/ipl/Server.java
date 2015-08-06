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
	private ReceivePort receiver;
	private final Deque<Board> deque;
	private Status status;
	private final AtomicInteger busyWorkers;
	private final AtomicInteger solutions;
	private BoardCache cache;

	Server(Ida parent) throws IOException
	{
		this.parent = parent;
		senders = new HashMap<IbisIdentifier, SendPort>();
		deque = new ArrayDeque<Board>();
		busyWorkers = new AtomicInteger(0);
		status = Status.INITIALIZING;
		solutions = new AtomicInteger(0);
	}

	/**
	 * Creates a receive port to receive board requests from workers.
	 * 
	 * @throws IOException
	 */
	private void openPorts() throws IOException
	{
		receiver = parent.ibis.createReceivePort(Ida.portType, "master", this, this, new Properties());
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
			sender.connect(worker, "worker");
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

	/**
	 * Waits until all workers have finished their work and sent the number of
	 * solutions.
	 */
	private void waitForWorkers()
	{
		synchronized (this)
		{
			status = Status.WAITING_FOR_WORKERS;
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

	/**
	 * Get the last board from the deque, which will have less twists and thus
	 * more work on average than boards from the start of the deque.
	 */
	private Board getLastBoard()
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
				board = deque.removeLast();
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
	 * Send a board to a worker.
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
		Board replyValue = getLastBoard(); // may block for some time
		sendBoard(replyValue, sender);

		// Increase the number of workers we are waiting for
		busyWorkers.incrementAndGet();
	}

	/**
	 * Processes the first board from the deque. This version is not recursive
	 * and slightly slower that the recursive one, but is easier to handle in
	 * the presence of other threads working on the deque.
	 *
	 * The
	 */
	private void processBoard(BoardCache cache, boolean first)
	{
		synchronized (deque)
		{
			// Get a board from the deque, null if deque is empty
			Board board;
			if (first)
			{
				board = deque.pollFirst();
			}
			else
			{
				board = deque.pollLast();
			}
			if (board == null)
			{
				status = Status.DEQUE_EMPTY;
				return;
			}

			// If the board is solved, increment the number of found solutions
			if (board.distance() == 1)
			{
				solutions.incrementAndGet();
				//				cache.put(board);
				if (deque.isEmpty())
				{
					status = Status.DEQUE_EMPTY;
				}
				return;
			}

			// Stop searching at the bound
			if (board.distance() > board.bound())
			{
				//				cache.put(board);
				if (deque.isEmpty())
				{
					status = Status.DEQUE_EMPTY;
				}
				return;
			}

			// Generate all possible boards from this one by twisting it in
			// every possible way. Gets new objects from the cache
			ArrayList<Board> boards = cache == null ? board.makeMoves() : board.makeMoves(cache);

			// Add all children to the beginning of the deque
			for (Board child : boards)
			{
				deque.addFirst(child);
			}
		}
	}

	/**
	 * Solves a Rubik's board by iteratively searching for solutions with a
	 * greater depth. This guarantees the optimal solution is found. Repeats all
	 * work for the previous iteration each iteration though...
	 *
	 * @param board
	 *            the board to solve
	 */
	private void solve(Board board) throws IOException
	{
		// cache used for board objects. Doing new Board() for every move
		// overloads the garbage collector

		int bound = board.bound();

		// determine how many boards we should process from the end of the deque
		// in order get it ready for boards
		int fillBoards = (int)Math.pow(senders.size() * (board.distance() - 1), 2);

		System.out.print("Bound now:");

		while (solutions.get() == 0)
		{
			status = Status.FILLING_DEQUE;

			board.setBound(bound);
			deque.addFirst(board);

			System.out.print(" " + bound);

			while (deque.size() < fillBoards && !deque.isEmpty())
			{
				processBoard(cache, false);
			}
			synchronized (deque)
			{
				if (!deque.isEmpty())
				{
					status = Status.PROCESSING_DEQUE;
					deque.notifyAll();
				}
			}
			while (!deque.isEmpty())
			{
				processBoard(cache, true);
			}
			waitForWorkers();
			bound++;
		}
		shutdown();

		System.out.println();
		System.out.println("Solving board possible in " + solutions + " ways of " + bound + " steps");
	}

	public void run(String fileName, boolean useCache) throws IOException
	{
		Board b = null;
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
				b = new Board(fileName);

				b.setBound(b.distance());
			}
			catch (Exception e)
			{
				System.err.println("could not initialize board from file: " + e);
				parent.ibis.registry().terminate();
				System.exit(1);
			}
		}
		if (b == null)
		{
			System.err.println("could not initialize board from file: " + fileName);
			parent.ibis.registry().terminate();
			System.exit(1);
		}

		if (useCache)
			cache = new BoardCache();
		System.out.println("Running IDA*, initial board:");
		System.out.println(b);

		// open Ibis ports
		openPorts();

		long start = System.currentTimeMillis();
		solve(b);
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("Solving IDA took " + (end - start) + " milliseconds");
	}
}