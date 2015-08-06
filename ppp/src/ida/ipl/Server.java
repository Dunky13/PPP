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

	private BoardCache cache;
	private final LinkedBlockingDeque<Board> deque;
	private Board initialBoard;
	private final Ida parent;
	private ReceivePort receiver;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private final AtomicInteger solutions;
	private boolean useCache;
	private final AtomicInteger busyWorkers;
	private boolean programWorking;

	public Server(Ida parent) throws IOException
	{
		this.parent = parent;
		senders = new HashMap<IbisIdentifier, SendPort>();
		deque = new LinkedBlockingDeque<Board>();
		solutions = new AtomicInteger(0);
		busyWorkers = new AtomicInteger(0);
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
				this.initialBoard = new Board(fileName);
				this.initialBoard.setBound(this.initialBoard.distance());
			}
			catch (Exception e)
			{
				closeIbisDueToError("could not initialize board from file: " + e);
			}
		}
		if (this.initialBoard == null)
		{
			closeIbisDueToError("could not initialize board from file: " + fileName);
		}
		if (this.useCache = useCache)
			this.cache = new BoardCache();
		System.out.println("Running IDA*, initial board:");
		System.out.println(this.initialBoard);

		// open Ibis ports
		openPorts();

		long start = System.currentTimeMillis();
		execute();
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
		busyWorkers.incrementAndGet();
		// Increase the number of workers we are waiting for
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

	private void execute() throws IOException
	{
		System.out.print("Try bound ");
		System.out.flush();
		System.out.print(" " + this.initialBoard.bound());
		do
		{
			int solution = processBoard(this.initialBoard);

			//Leave the harder tasks for the slaves
			solution += doCalculation();

			this.solutions.addAndGet(solution);
			waitForWorkers();
		} while (incrementBound());
		shutdown();

		System.out.println();
		System.out.println("Solving board possible in " + solutions + " ways of " + initialBoard.bound() + " steps");
	}

	private boolean incrementBound()
	{
		this.programWorking = this.solutions.get() == 0;
		if (this.programWorking)
		{
			int bound = this.initialBoard.bound() + 1;
			this.initialBoard.setBound(bound);
			System.out.print(" " + bound);
		}
		return this.programWorking;
	}

	private int doCalculation()
	{
		int solutions = 0;
		while (!deque.isEmpty())
		{
			Board board = getBoard(true);
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
			if (this.useCache)
			{
				ArrayList<Board> boards = board.makeMoves(this.cache);
				for (int i = 0; i < boards.size(); i++)
				{
					Board child = boards.get(i);
					if (i < boards.size() / 2)
						deque.addFirst(child);
					else
					{
						processBoard(child);
						this.cache.put(child);
					}
				}
			}
			else
			{
				ArrayList<Board> boards = board.makeMoves();
				for (int i = 0; i < boards.size(); i++)
				{
					Board child = boards.get(i);
					if (i < boards.size() / 2)
						deque.addFirst(child);
					else
						processBoard(child);
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