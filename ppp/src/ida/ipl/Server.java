package ida.ipl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedDeque;
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

	private final SharedData data; //SharedData object is used to share the data that is accessible to the Server class between threads (The solving thread and Upcall threads)

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
				data.setInitialBoard(new Board(fileName));
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
		try
		{
			execution();
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
			SendPort sender = data.getParent().ibis.createSendPort(Ida.portType);
			sender.connect(worker, "slave");
			data.getSenders().put(worker, sender);
			synchronized (data.getWaitingForWork())
			{
				data.getWaitingForWork().push(worker);
				data.getWaitingForWork().notifyAll();
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
			SendPort sender = data.getSenders().get(worker);
			sender.close();
			data.getSenders().remove(worker);
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

	@SuppressWarnings("unchecked")
	@Override
	public void upcall(ReadMessage rm) throws IOException, ClassNotFoundException
	{
		// Process the incoming message and decrease the number of busy workers
		IbisIdentifier sender = rm.origin().ibisIdentifier();
		int requestValue = 0;
		ArrayList<Board> boards = null;
		boolean recvInt = rm.readBoolean();

		data.getWaitingForWork().add(sender);
		if (recvInt)
		{
			requestValue = rm.readInt();
			rm.finish();
			if (requestValue != Ida.INIT_VALUE && requestValue != Slave.NO_BOARD) //Solution received
			{
				data.getSolutions().addAndGet(requestValue);
			}
		}
		else
		{
			boards = (ArrayList<Board>)rm.readObject();
			rm.finish();
			setBoards(boards);
		}

		Board replyValue = getBoardAfterWait(); // may block for some time
		if (sendBoard(replyValue, sender))
			data.getWaitingForWork().remove(sender);
	}

	private boolean sendBoard(Board board, IbisIdentifier destination) throws IOException
	{
		if (data.isFinished())
			return false;
		SendPort port = data.getSenders().get(destination);
		WriteMessage wm = port.newMessage();
		wm.writeBoolean(false);
		wm.writeObject(board);
		wm.finish();
		return true;
	}

	private void execution() throws InterruptedException, IOException
	{

		Board initialBoard = data.getInitialBoard();
		int bound = initialBoard.distance();

		System.out.print("Try bound ");
		System.out.flush();
		initialBoard.setBound(bound);
		data.setInitialBoard(initialBoard);

		System.out.print(" " + bound);
		//		synchronized (this)
		//		{
		//			this.wait();
		//		}
		Thread t = new Thread(new Runnable()
		{

			@Override
			public void run()
			{
				try
				{
					while (!data.isFinished())
					{
						calculateJob();

						//						synchronized (deque)
						//						{
						//							if (finished || deque.isEmpty() && solutions.get() > 0 && waitingForWork.size() == senders.size())
						//							{
						//								finished = true;
						//								break;
						//							}
						//						}
					}

				}
				catch (IOException e)
				{
				}
				synchronized (lock)
				{
					lock.notifyAll();
				}
			}
		});
		t.start();
		synchronized (lock)
		{
			lock.wait();
		}

		shutdown();

		System.out.print("\nresult is " + data.getSolutions().get() + " solutions of " + data.getInitialBoard().bound() + " steps");
		System.out.flush();
	}

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

		Board b = getBoardAfterWait();
		if (b == null)
		{
			data.setFinished(true);

			synchronized (lock)
			{
				lock.notifyAll();
			}

			return;
		}
		int solution = calcJob(b);
		if (solution > 0)
			data.getSolutions().addAndGet(solution);
		//		if (b == null)
		//			return;
		//		if (b.distance() == 1)
		//			solutions.addAndGet(1);
		//		else if (b.distance() > b.bound())
		//			solutions.addAndGet(0);
		//		else
		//		{
		//			ArrayList<Board> boards = cache == null ? b.makeMoves() : b.makeMoves(cache);
		//			setBoards(boards);
		//		}
	}

	private int calcJob(Board b)
	{
		if (b == null)
			return -1;
		if (b.distance() == 1)
			return 1;
		else if (b.distance() > b.bound())
			return 0;
		else
		{
			ArrayList<Board> boards = !data.useCache() ? b.makeMoves() : b.makeMoves(data.getCache());
			if (data.getDeque().size() < 32)
			{
				Board b3 = boards.remove(0);
				setBoards(boards);
				return calcJob(b3);
			}
			else
			{
				int result = 0;
				for (Board b2 : boards)
					result += calcJob(b2);
				return result;
			}
		}
	}

	private Board getBoardAfterWait()
	{
		if (data.isFinished())
			return null;
		Board b = null;
		do
			waitForQueue();
		while ((b = getBoard()) == null && !data.isFinished());
		return b;
	}

	private void waitForQueue()
	{
		ConcurrentLinkedDeque<Board> deque = data.getDeque();
		synchronized (deque)
		{
			while (deque.isEmpty() && data.getWaitingForWork().size() < data.getSenders().size())
			{
				try
				{
					deque.wait(100);
				}
				catch (InterruptedException e)
				{
				}
			}
			if (deque.isEmpty() && data.getWaitingForWork().size() == data.getSenders().size())
				incrementBound();
		}
	}

	private Board getBoard()
	{
		Board board = null;
		ConcurrentLinkedDeque<Board> deque = data.getDeque();
		if (deque.isEmpty()) //Bound finished and no result found
			return null;
		board = deque.pop();
		return board;
	}

	private void setBoards(ArrayList<Board> boards)
	{
		ConcurrentLinkedDeque<Board> deque = data.getDeque();
		for (Board b : boards)
		{
			deque.add(b);
		}
		synchronized (deque)
		{
			deque.notify();
		}
	}

	private void incrementBound()
	{
		if (data.getSolutions().get() > 0)
		{
			synchronized (lock)
			{
				lock.notifyAll();
				data.setFinished(true);
			}
			return;
		}
		ConcurrentLinkedDeque<Board> deque = data.getDeque();
		if (!deque.isEmpty())
			return;
		synchronized (data.getInitialBoard())
		{
			int bound = data.getInitialBoard().bound() + 1;
			data.getInitialBoard().setBound(bound);
			System.out.print(" " + bound);
			data.setInitialBoard(data.getInitialBoard());
		}
	}
}