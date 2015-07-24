package ida.ipl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
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
	private final static Object lock = new Object();

	private final SharedData data; // SharedData object is used to share the
	// data that is accessible to the Server
	// class between threads (The solving thread
	// and Upcall threads)

	private Runnable calculation = new Runnable()
	{

		@Override
		public void run()
		{
			do
			{
				calculateQueueBoard();
			} while (!data.programFinished());
			programFinished(true);
		}
	};

	public Server(Ida parent)
	{
		this.data = new SharedData(parent);
	}

	public void run(String fileName, boolean useCache) throws IOException
	{

		if (fileName == null)
		{
			System.err.println("No input file provided.");
			data.getParent().ibis.registry().terminate();
			System.exit(1);
		}
		else
		{
			try
			{
				Board b = new Board(fileName);
				b.setBound(b.distance());
				data.getCurrentBound().set(b.distance());
				data.setInitialBoard(b);
			}
			catch (Exception e)
			{
				System.err.println("could not initialize board from file: " + e);
				data.getParent().ibis.registry().terminate();
				System.exit(1);
			}
		}
		if (useCache)
			data.setCache(new BoardCache());
		System.out.println("Running IDA*, initial board:");
		System.out.println(data.getInitialBoard());

		// open Ibis ports
		openPorts();

		long start = System.currentTimeMillis();
		execution();
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
			SendPort sender = data.getParent().ibis.createSendPort(Ida.portType);
			sender.connect(worker, "slave");
			data.getSenders().put(worker, sender);
			data.calculateMinimumQueueSize();
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
			SendPort sender = data.getSenders().get(worker);
			sender.close();
			data.getSenders().remove(worker);
			data.getNodesWaiting().decrementAndGet();
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

	@Override
	public void upcall(ReadMessage rm) throws IOException
	{
		// Process the incoming message and decrease the number of busy workers
		IbisIdentifier sender = rm.origin().ibisIdentifier();
		data.getNodesWaiting().incrementAndGet();

		int requestValue = rm.readInt();
		rm.finish();
		// Solution received
		if (requestValue > 0)
			data.getSolutions().addAndGet(requestValue);

		if (data.programFinished())
			return;

		Board replyValue = getBoardAfterWait(); // may block for some time
		if (sendBoard(replyValue, sender))
			data.getNodesWaiting().decrementAndGet();
	}

	private boolean sendBoard(Board board, IbisIdentifier destination)
	{
		SendPort port = data.getSenders().get(destination);
		if (data.programFinished())
			return false;
		try
		{
			WriteMessage wm = port.newMessage();
			wm.writeBoolean(false);
			wm.writeObject(board);
			wm.finish();
			return true;
		}
		catch (IOException e)
		{
			return false;
		}
	}

	private void execution() throws IOException
	{

		System.out.print("Try bound ");
		System.out.flush();

		System.out.print(" " + data.getCurrentBound().get());

		// Run the solving on server side in a seperate thread so it does not
		// block upcalls or the lock that waits until ALL calculations are
		// finished.
		Thread t = new Thread(this.calculation);
		t.start();

		// Wait for ALL calclations to finish and to find a solution.
		SharedData.wait(lock);

		shutdown();

		System.out.print("\nresult is " + data.getSolutions().get() + " solutions of " + data.getInitialBoard().bound() + " steps");
		System.out.flush();
	}

	/**
	 * Open a receiving port that allows upcalls to happen.
	 * 
	 * @throws IOException
	 */
	private void openPorts() throws IOException
	{
		ReceivePort receiver = data.getParent().ibis.createReceivePort(Ida.portType, "server", this, this, new Properties());
		receiver.enableConnections();
		receiver.enableMessageUpcalls();
		data.setReceiver(receiver);
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
		data.getParent().ibis.registry().terminate();

		// Close ports (and send termination messages)
		try
		{
			for (SendPort sender : data.getSenders().values())
			{
				WriteMessage wm = sender.newMessage();
				wm.writeBoolean(true);
				wm.finish();
				sender.close();
			}
			data.getReceiver().close();
		}
		catch (ConnectionClosedException e)
		{
			// do nothing
		}
	}

	/**
	 * Looped to get boards from the queue
	 * 
	 * @throws IOException @throws
	 */
	private void calculateQueueBoard()
	{

		data.getNodesWaiting().decrementAndGet();
		Board b = getBoardAfterWait();
		if (b == null && programFinished(data.programFinished()))
			return;
		calculateBoardSolution(b);
		data.getNodesWaiting().incrementAndGet();
	}

	private void calculateBoardSolution(Board b)
	{
		if (b == null) // Should happen only if finished and needs to be caught to prevent null pointer exceptions.
			return;
		if (b.distance() == 1)
			data.getSolutions().incrementAndGet();
		else if (b.distance() > b.bound())
			return;
		else
		{
			ArrayList<Board> boards = data.useCache() ? b.makeMoves(data.getCache()) : b.makeMoves();
			if (!boards.isEmpty() && data.getDeque().size() < data.getMinimalQueueSize()) // If queue not full 'enough' fill it so the slaves have something to do as well.
			{
				Board tmpBoard = boards.remove(0); // Calculate only one board instead of all
				data.addBoards(boards);
				calculateBoardSolution(tmpBoard);
			}
			else // Queue is full enough for the slaves to work so loop over all results to get the solutions.
			{
				for (Board tmpBoard2 : boards)
					calculateBoardSolution(tmpBoard2);
			}
		}
	}

	/**
	 * Returns board from queue, if queue is empty wait for queue to be filled
	 * again.
	 * 
	 * Blocking property
	 * 
	 * @return Board
	 * @throws InterruptedException
	 */
	private Board getBoardAfterWait()
	{
		if (!incrementBound())
			return null;
		Board b = data.getBoard();
		return b;
	}

	/**
	 * Increment bound of initialBoard unless solutions are found.
	 */
	private boolean incrementBound()
	{
		if (programFinished(data.programFinished()))
			return false;
		if (data.boundFinished())
			data.incrementBound();
		return true;
	}

	private boolean programFinished(boolean progFinished)
	{
		if (progFinished)
			SharedData.notifyAll(lock);
		return progFinished;
	}
}