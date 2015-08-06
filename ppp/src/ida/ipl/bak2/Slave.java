package ida.ipl.bak2;

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

	public static final int NO_BOARD = -2;
	public static final int SENT_BOARD = -1;
	private BoardCache cache;
	private ReceivePort masterReceived;
	private SendPort masterSend;
	private final Ida parent;

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

		if (shouldClose) // Shutdown received and close the node.
		{
			rm.finish();
			shutdown();
		}
		else
		{
			Board board = (Board)rm.readObject(); // Read board from the message.
			rm.finish();
			if (board == null) // prevent null pointer exceptions
			{
				sendInt(Slave.NO_BOARD); // If in some miraculous case no board is received return an error to the Master, this also ensures the slave is kept
				// in the loop of messages.
				return;
			}
			int solution = calculateJob(board);
			sendInt(solution);
		}
	}

	/**
	 * Calculate the job
	 * 
	 * @param b
	 * @return int Solution
	 * @throws IOException
	 */
	private int calculateJob(Board b) throws IOException
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
			int solution = 0;
			for (Board board : boards)
			{
				solution += calculateJob(board);
			}
			return solution;
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

	/**
	 * Send solution
	 * 
	 * @param value
	 * @throws IOException
	 */
	private void sendInt(int value) throws IOException
	{
		WriteMessage wm = masterSend.newMessage();
		wm.writeInt(value);
		wm.finish();
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
}