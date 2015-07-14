package ida.ipl;

import java.io.IOException;
import java.util.ArrayList;
import ibis.ipl.ConnectionClosedException;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;

public class Slave implements MessageUpcall
{

	public static final int SENT_BOARD = -1;
	public static final int NO_BOARD = -2;
	private final Ida parent;
	private BoardCache cache;
	private ReceivePort masterReceived;
	private SendPort masterSend;

	public Slave(Ida parent)
	{
		this.parent = parent;
	}

	public void run(IbisIdentifier master, boolean useCache) throws IOException, ClassNotFoundException, InterruptedException
	{
		if (useCache)
		{
			cache = new BoardCache();
		}
		// Open send and receive ports
		openPorts(master);

		// Send an initialization message
		sendInt(Ida.INIT_VALUE);

		// Make sure this thread doesn't finish prematurely
		synchronized (this)
		{
			this.wait();
		}
	}

	@Override
	public void upcall(ReadMessage rm) throws IOException, ClassNotFoundException
	{
		boolean shouldClose = rm.readBoolean();

		if (shouldClose)
		{
			rm.finish();
			shutdown();
		}
		else
		{
			boolean replyBoard = rm.readBoolean();
			Board board = (Board)rm.readObject();
			rm.finish();
			if (board == null) //prevent null pointer exceptions 
			{
				sendInt(Slave.NO_BOARD);
				return;
			}
			int solution = calculateJob(board, replyBoard);
			if (!replyBoard && solution >= 0)
				sendInt(solution);
		}
	}

	private int calculateJob(Board b, boolean replyBoard) throws IOException
	{
		if (b.distance() == 1)
		{
			return 1;
		}
		else if (b.distance() > b.bound())
		{
			return 0;
		}
		else
		{
			ArrayList<Board> boards = cache == null ? b.makeMoves() : b.makeMoves(cache);
			if (replyBoard)
			{
				sendBoards(boards);
				return Slave.SENT_BOARD;
			}
			else
			{
				int solution = 0;
				int tmpSolution = 0;
				for (Board board : boards)
				{
					tmpSolution = calculateJob(board, replyBoard);
					if (tmpSolution > 0)
						solution += tmpSolution;
				}
				return solution;
			}
		}
	}

	private void openPorts(IbisIdentifier master) throws IOException
	{
		masterReceived = parent.ibis.createReceivePort(Ida.portType, "slave", this);
		masterReceived.enableConnections();
		masterReceived.enableMessageUpcalls();

		masterSend = parent.ibis.createSendPort(Ida.portType);
		masterSend.connect(master, "server");
	}

	private void shutdown() throws IOException
	{
		// Close the ports
		try
		{
			masterSend.close();
			masterReceived.close();
		}
		catch (ConnectionClosedException e)
		{
			// do nothing
		}

		// Notify the main thread
		synchronized (this)
		{
			this.notify();
		}
	}

	private void sendBoards(ArrayList<Board> boards) throws IOException
	{
		WriteMessage wm = masterSend.newMessage();
		wm.writeBoolean(false);
		wm.writeObject(boards);
		wm.finish();
	}

	private void sendInt(int value) throws IOException
	{
		WriteMessage wm = masterSend.newMessage();
		wm.writeBoolean(true);
		wm.writeInt(value);
		wm.finish();
	}
}