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

	private final SharedData data; // SharedData object is used to share the
	// data that is accessible to the Server
	// class between threads (The solving thread
	// and Upcall threads)

	private ServerCalculator calculation;

	public Server(Ida parent)

	{
		this.data = new SharedData(parent);
		this.calculation = new ServerCalculator(data);
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
		if (requestValue > 0)
			data.getSolutions().addAndGet(requestValue);

		if (data.programFinished())
			return;

		if (sendBoard(data.getBoards(SharedData.numberOfBoardsPerSend, false), sender))
			data.getNodesWaiting().decrementAndGet();
	}

	private boolean sendBoard(ArrayList<Board> boards, IbisIdentifier destination)
	{
		boolean programFinished = data.programFinished();
		SendPort port = data.getSenders().get(destination);
		if (port == null)
			return false;
		try
		{
			WriteMessage wm = port.newMessage(); //data.getNewMessage(port);
			wm.writeBoolean(programFinished);
			wm.writeInt(boards.size());
			for (Board b : boards)
				wm.writeObject(b);
			wm.finish();
			return !programFinished;
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

		// Wait for ALL calclations to finish and to find a solution.
		this.calculation.execute();

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

	private class ServerCalculator implements Runnable
	{
		SharedData data;

		ServerCalculator(SharedData data)
		{
			this.data = data;
		}

		public void run()
		{
			execute();
		}

		public void execute()
		{
			do
			{
				data.getNodesWaiting().incrementAndGet();
				Board b = data.getBoard(true);
				data.getNodesWaiting().decrementAndGet();

				calculateQueueBoard(b);

			} while (!data.programFinished());
		}

		/**
		 * Looped to get boards from the queue
		 * 
		 * @throws IOException
		 * 			@throws
		 */
		private void calculateQueueBoard(Board b)
		{

			if (b == null && data.programFinished())
			{
				data.getNodesWaiting().incrementAndGet();
				return;
			}
			if (b == null) // Should happen only if finished and needs to be caught to prevent null pointer exceptions.
				return;

			int solution = calculateBoardSolution(b);
			if (solution > 0)
				data.getSolutions().addAndGet(solution);

		}

		private int calculateBoardSolution(Board b)
		{
			if (b.distance() == 1)
				return 1;
			else if (b.distance() > b.bound())
				return 0;
			else
			{
				ArrayList<Board> boards = data.useCache() ? b.makeMoves(data.getCache()) : b.makeMoves();
				if (boards.isEmpty())
					return 0;
				checkEnoughMoves(boards);
				int solution = 0;
				for (Board tmpBoard2 : boards)
					solution += calculateBoardSolution(tmpBoard2);
				data.getCache().put(boards);
				return solution;
			}
		}

		private void checkEnoughMoves(ArrayList<Board> boards)
		{
			int boardsToAdd = 0;
			if ((boardsToAdd = data.addMoreBoardsToQueue()) > 0) // If queue not full 'enough' fill it so the slaves have something to do as well.
				data.addBoards(splitMoves(boards, boardsToAdd));
			//Get the necessary boards to fill the queue
		}

		private ArrayList<Board> splitMoves(ArrayList<Board> boards, int size)
		{
			ArrayList<Board> tmpBoards = new ArrayList<Board>();
			int boardSize = boards.size();
			size = size > boardSize ? boardSize : size;
			for (int i = 1; i <= size; i++)
			{
				tmpBoards.add(boards.remove(boardSize - i));
			}
			return tmpBoards;
		}
	}
}