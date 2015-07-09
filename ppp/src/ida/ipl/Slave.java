package ida.ipl;

import ibis.ipl.ConnectionClosedException;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.MessageUpcall;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;
import java.io.IOException;
import java.util.ArrayList;

public class Slave implements MessageUpcall
{

	private final Ida parent;
	private BoardCache cache;
	private ReceivePort receiver;
	private SendPort sender;

	Slave(Ida parent)
	{
		this.parent = parent;
		cache = null;
	}

	void openPorts(IbisIdentifier master) throws IOException
	{
		receiver = parent.ibis.createReceivePort(Ida.portType, "slave", this);
		receiver.enableConnections();
		receiver.enableMessageUpcalls();

		sender = parent.ibis.createSendPort(Ida.portType);
		sender.connect(master, "server");
	}

	public void shutdown() throws IOException
	{
		// Close the ports
		try
		{
			sender.close();
			receiver.close();
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

	void run(IbisIdentifier master) throws IOException, ClassNotFoundException, InterruptedException
	{
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
		boolean shouldClose = rm.readBoolean();
		if (shouldClose)
		{
			rm.finish();
			shutdown();
		}
		else
		{
			// Process the cube and send back the number of solutions
			Board board = (Board)rm.readObject();
			rm.finish();
			if (cache == null)
			{
				cache = new BoardCache();
			}
			sendInt(solutions(board, cache));
		}
	}

	void sendInt(int value) throws IOException
	{
		WriteMessage wm = sender.newMessage();
		wm.writeInt(value);
		wm.finish();
	}

	/**
	 * Recursive function to find a solution for a given cube. Only searches to
	 * the bound set in the cube object.
	 *
	 * @param cube
	 *            cube to solve
	 * @param cache
	 *            cache of cubes used for new cube objects
	 * @return the number of solutions found
	 */
	public int solutions(Board board, BoardCache cache)
	{
		if (board.distance() == 1)
		{
			return 1;
		}

		if (board.distance() > board.bound())
		{
			return 0;
		}

		ArrayList<Board> moves = cache == null ? board.makeMoves() : board.makeMoves(cache);
		int result = 0;

		for (Board child : moves)
		{
			result += solutions(child, cache);
		}
		cache.put(moves);

		return result;
	}
}