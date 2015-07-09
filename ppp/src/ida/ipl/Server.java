package ida.ipl;

import ibis.ipl.ConnectionClosedException;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortConnectUpcall;
import ibis.ipl.SendPort;
import ibis.ipl.SendPortIdentifier;
import ibis.ipl.WriteMessage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

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

	/**
	 * Get the last cube from the deque, which will have less twists and thus
	 * more work on average than cubes from the start of the deque.
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

		// Get the port to the sender and send the cube
		Board replyValue = getLastBoard(); // may block for some time
		sendBoard(replyValue, sender);

		// Increase the number of workers we are waiting for
		busyWorkers.incrementAndGet();
	}

	/**
	 * Processes the first cube from the deque. This version is not recursive
	 * and slightly slower that the recursive one, but is easier to handle in
	 * the presence of other threads working on the deque.
	 *
	 * The
	 */
	private void processCube(BoardCache cache)
	{
		synchronized (deque)
		{
			Board board = deque.poll();
			if (board == null || deque.isEmpty())
				status = Status.DEQUE_EMPTY;
			if (board == null)
				return;

			if (board.distance() == 1)
			{
				solutions.incrementAndGet();
				return;
			}

			if (board.distance() > board.bound())
				return;

			deque.addAll(cache == null ? board.makeMoves() : board.makeMoves(cache));
		}
	}

	/**
	 * Solves a Rubik's cube by iteratively searching for solutions with a
	 * greater depth. This guarantees the optimal solution is found. Repeats all
	 * work for the previous iteration each iteration though...
	 *
	 * @param cube
	 *            the cube to solve
	 */
	private void solve(Board board, boolean useCache) throws InterruptedException, IOException
	{
		BoardCache cache = null;
		if (useCache)
		{
			cache = new BoardCache();
		}

		int bound = board.distance();

		System.out.print("Try bound ");
		System.out.flush();

		do
		{
			status = Status.FILLING_DEQUE;

			board.setBound(bound);
			deque.addFirst(board);

			System.out.print(" " + bound);

			while (!deque.isEmpty())
			{
				processCube(cache);
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
				processCube(cache);
			}
			waitForWorkers();
			bound++;
		} while (solutions.get() == 0);
		shutdown();

		System.out.print("\nresult is " + solutions.get() + " solutions of " + board.bound() + " steps");
		System.out.flush();
	}

	/**
	 * Main function.
	 *
	 * @param arguments
	 *            list of arguments
	 */
	public void run(String[] args) throws IOException, InterruptedException
	{

		String fileName = null;
		boolean cache = true;

		for (int i = 0; i < args.length; i++)
		{
			if (args[i].equals("--file"))
			{
				fileName = args[++i];
			}
			else if (args[i].equals("--nocache"))
			{
				cache = false;
			}
			else
			{
				System.err.println("No such option: " + args[i]);
				parent.ibis.registry().terminate();
				System.exit(1);
			}
		}

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
		solve(initialBoard, cache);
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("Solving cube took " + (end - start) + " milliseconds");
	}
}