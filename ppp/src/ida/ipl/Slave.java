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
		// Check whether we should terminate or not
		System.out.println("Received message " + rm.sequenceNumber());
		boolean shouldClose = rm.readBoolean();
		if (shouldClose)
		{
			rm.finish();
			shutdown();
		}
		else
		{
			// Process the cube and send back the number of solutions
			System.out.println("Received board");
			Board board = (Board)rm.readObject();
			rm.finish();
			calculateJob(board);
		}
	}

	private void calculateJob(Board b) throws IOException
	{
		if (b.distance() == 1)
		{
			System.out.println("Found answer");
			sendInt(1);
		}
		else if (b.distance() > b.bound())
		{
			System.out.println("Out of bound");
			sendInt(0);
		}
		else
		{
			System.out.println("Sending boards");
			sendBoards(cache == null ? b.makeMoves() : b.makeMoves(cache));
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