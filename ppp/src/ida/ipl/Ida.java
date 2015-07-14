package ida.ipl;

import ibis.ipl.Ibis;
import ibis.ipl.IbisCapabilities;
import ibis.ipl.IbisCreationFailedException;
import ibis.ipl.IbisFactory;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.PortType;

/**
 * Solver for Rubik's cube puzzle
 */
public class Ida
{

	public final static int INIT_VALUE = -1;

	public final static PortType portType = new PortType(
		PortType.COMMUNICATION_RELIABLE,
		PortType.SERIALIZATION_OBJECT_IBIS,
		PortType.RECEIVE_AUTO_UPCALLS,
		PortType.CONNECTION_MANY_TO_ONE,
		PortType.CONNECTION_UPCALLS,
		PortType.SERIALIZATION_OBJECT,
		PortType.COMMUNICATION_NUMBERED);

	public final static IbisCapabilities ibisCapabilities = new IbisCapabilities(
		IbisCapabilities.ELECTIONS_STRICT,
		IbisCapabilities.TERMINATION);

	public final Ibis ibis;

	Ida() throws IbisCreationFailedException
	{
		ibis = IbisFactory.createIbis(ibisCapabilities, null, portType, portType);
	}

	void run(String[] args) throws Exception
	{
		// Wait until all ibises joined
		//ibis.registry().waitUntilPoolClosed();

		String fileName = null;
		boolean useCache = true;

		for (int i = 0; i < args.length; i++)
		{
			if (args[i].equals("--file"))
			{
				fileName = args[++i];
			}
			else if (args[i].equals("--nocache"))
			{
				useCache = false;
			}
			else
			{
				System.err.println("No such option: " + args[i]);
				ibis.registry().terminate();
				System.exit(1);
			}
		}

		// Elect a master
		IbisIdentifier master = ibis.registry().elect("Master");

		// If I am the master, run master, else run worker
		if (master.equals(ibis.identifier()))
		{
			new Server(this).run(fileName, useCache);
		}
		else
		{
			new Slave(this).run(master, useCache);
		}

		// End ibis
		ibis.end();
	}

	public static void main(String[] arguments)
	{
		try
		{
			new Ida().run(arguments);
		}
		catch (Exception e)
		{
			e.printStackTrace(System.err);
		}
	}
}

//
//import ibis.ipl.Ibis;
//import ibis.ipl.IbisCapabilities;
//import ibis.ipl.IbisFactory;
//import ibis.ipl.IbisIdentifier;
//import ibis.ipl.MessageUpcall;
//import ibis.ipl.PortType;
//import ibis.ipl.ReadMessage;
//import ibis.ipl.ReceivePort;
//import ibis.ipl.SendPort;
//import ibis.ipl.WriteMessage;
//import ibis.util.Queue;
//import java.io.IOException;
//import java.util.ArrayList;
//
//public class Ida implements MessageUpcall
//{
//	/**
//	 * Port type used for sending a request to the server
//	 */
//	PortType requestPortType = new PortType(
//		PortType.COMMUNICATION_RELIABLE,
//		PortType.SERIALIZATION_OBJECT,
//		PortType.RECEIVE_AUTO_UPCALLS,
//		PortType.CONNECTION_MANY_TO_ONE);
//
//	/**
//	 * Port type used for sending a reply back
//	 */
//	PortType replyPortType = new PortType(
//		PortType.COMMUNICATION_RELIABLE,
//		PortType.SERIALIZATION_DATA,
//		PortType.RECEIVE_EXPLICIT,
//		PortType.CONNECTION_ONE_TO_ONE);
//
//	IbisCapabilities ibisCapabilities = new IbisCapabilities(IbisCapabilities.ELECTIONS_STRICT);
//
//	private Ibis myIbis;
//
//	private ReceivePort serverReceiver;
//
//	private Queue serverQueue;
//	private Integer serverSolution;
//
//	private static void solve(Board board, boolean useCache)
//	{
//		BoardCache cache = null;
//		if (useCache)
//		{
//			cache = new BoardCache();
//		}
//
//		int bound = board.distance();
//		int solutions = 0;
//
//		System.out.print("Try bound ");
//		System.out.flush();
//
//		do
//		{
//			board.setBound(bound);
//
//			System.out.print(bound + " ");
//			System.out.flush();
//
//			if (useCache)
//			{
//				solutions = solutions(board, cache);
//			}
//			else
//			{
//				solutions = solutions(board);
//			}
//
//			bound++;
//		} while (solutions == 0);
//
//		System.out.print("\nresult is " + solutions + " solutions of " + board.bound() + " steps");
//		System.out.flush();
//	}
//
//	/**
//	 * expands this board into all possible positions, and returns the number of
//	 * solutions. Will cut off at the bound set in the board.
//	 */
//	private static int solutions(Board board)
//	{
//
//		if (board.distance() == 1)
//		{
//			return 1;
//		}
//
//		//		System.out.println(board.toString(level));
//
//		if (board.distance() > board.bound())
//		{
//			return 0;
//		}
//
//		ArrayList<Board> moves = board.makeMoves();
//		int result = 0;
//
//		for (Board newBoard : moves)
//		{
//			if (newBoard != null)
//			{
//				result += solutions(newBoard);
//			}
//		}
//		return result;
//	}
//
//	/**
//	 * expands this board into all possible positions, and returns the number of
//	 * solutions. Will cut off at the bound set in the board.
//	 */
//	private static int solutions(Board board, BoardCache cache)
//	{
//		if (board.distance() == 1)
//		{
//			return 1;
//		}
//
//		if (board.distance() > board.bound())
//		{
//			return 0;
//		}
//
//		ArrayList<Board> moves = board.makeMoves(cache);
//		int result = 0;
//
//		for (Board newBoard : moves)
//		{
//			if (newBoard != null)
//			{
//				result += solutions(newBoard, cache);
//			}
//		}
//		cache.put(moves);
//
//		return result;
//	}
//
//	public static void main(String[] args)
//	{
//		String fileName = null;
//		boolean cache = true;
//
//		for (int i = 0; i < args.length; i++)
//		{
//			if (args[i].equals("--file"))
//			{
//				fileName = args[++i];
//			}
//			else if (args[i].equals("--nocache"))
//			{
//				cache = false;
//			}
//			else
//			{
//				System.err.println("No such option: " + args[i]);
//				System.exit(1);
//			}
//		}
//
//		Board initialBoard = null;
//
//		if (fileName == null)
//		{
//			System.err.println("No input file provided.");
//			System.exit(1);
//		}
//		else
//		{
//			try
//			{
//				initialBoard = new Board(fileName);
//			}
//			catch (Exception e)
//			{
//				System.err.println("could not initialize board from file: " + e);
//				System.exit(1);
//			}
//		}
//		System.out.println("Running IDA*, initial board:");
//		System.out.println(initialBoard);
//
//		long start = System.currentTimeMillis();
//		//solve(initialBoard, cache);
//		try
//		{
//			new Ida().run(initialBoard, cache);
//		}
//		catch (Exception e)
//		{
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		long end = System.currentTimeMillis();
//
//		// NOTE: this is printed to standard error! The rest of the output is
//		// constant for each set of parameters. Printing this to standard error
//		// makes the output of standard out comparable with "diff"
//		System.err.println("ida took " + (end - start) + " milliseconds");
//	}
//
//	private void run(Board board, boolean useCache) throws Exception
//	{
//		// Create an ibis instance.
//		// Notice createIbis uses varargs for its parameters.
//		myIbis = IbisFactory.createIbis(ibisCapabilities, null, requestPortType, replyPortType);
//
//		// Elect a server
//		IbisIdentifier server = myIbis.registry().elect("Server");
//
//		// If I am the server, run server, else run client.
//		if (server.equals(myIbis.identifier()))
//		{
//			server(board);
//		}
//		else
//		{
//			client(server, useCache);
//		}
//
//		// End ibis.
//		myIbis.end();
//	}
//
//	private void server(Board board) throws IOException, ClassNotFoundException
//	{
//		serverReceiver = myIbis.createReceivePort(requestPortType, "server", this);
//		serverReceiver.enableConnections();
//		serverReceiver.enableMessageUpcalls();
//		int bound = board.distance();
//
//		System.out.print("Try bound ");
//		System.out.flush();
//
//		do
//		{
//			serverQueue = new Queue();
//			serverSolution = 0;
//			board.setBound(bound);
//
//			System.out.print(bound + " ");
//			System.out.flush();
//			int count = 0;
//			while (serverQueue != null && serverQueue.size() > 0)
//			{
//				try
//				{
//					serverQueue.wait(100 + count * 50);
//				}
//				catch (InterruptedException e)
//				{
//				}
//				count++;
//			}
//
//			bound++;
//		} while (serverSolution.intValue() == 0);
//
//		System.out.print("\nresult is " + serverSolution + " solutions of " + board.bound() + " steps");
//		System.out.flush();
//
//		serverReceiver.close();
//	}
//
//	private void client(IbisIdentifier server, boolean useCache) throws IOException, ClassNotFoundException
//	{
//		BoardCache cache = null;
//		if (useCache)
//		{
//			cache = new BoardCache();
//		}
//
//		SendPort sendPort = myIbis.createSendPort(requestPortType);
//		sendPort.connect(server, "server");
//
//		ReceivePort receivePort = myIbis.createReceivePort(replyPortType, null);
//		receivePort.enableConnections();
//
//		// Send the request message. This message contains the identifier of
//		// our receive port so the server knows where to send the reply
//		WriteMessage request = sendPort.newMessage();
//		request.writeObject(receivePort.identifier());
//		request.finish();
//
//		ReadMessage reply = receivePort.receive();
//		Object result = reply.readObject();
//		while (result != null)
//		{
//			if (result instanceof Integer)
//			{
//				if (Math.abs((Integer)result) >= 10)
//					break;
//				int wait = Math.abs(((Integer)result) * 100);
//				try
//				{
//					wait(wait);
//				}
//				catch (InterruptedException e)
//				{
//				}
//				request = sendPort.newMessage();
//				request.writeObject(new Double(Math.abs(((Integer)result).doubleValue())));
//				request.finish();
//				reply = receivePort.receive();
//				result = reply.readObject();
//				continue;
//			}
//
//			reply.finish();
//			Board b = (Board)result;
//			request = sendPort.newMessage();
//			if (b.distance() == 1)
//			{
//				request.writeInt(1);
//				request.finish();
//			}
//			else if (b.distance() > b.bound())
//			{
//				request.writeInt(0);
//				request.finish();
//			}
//			else
//			{
//				ArrayList<Board> moves = useCache ? b.makeMoves(cache) : b.makeMoves();
//				if (useCache)
//					cache.put(moves);
//				request.writeObject(moves);
//				request.finish();
//
//			}
//			reply = receivePort.receive();
//			result = reply.readObject();
//		}
//
//		reply.finish();
//		// Close ports.
//		sendPort.close();
//		receivePort.close();
//	}
//
//	@SuppressWarnings("unchecked")
//	@Override
//	public void upcall(ReadMessage message) throws IOException, ClassNotFoundException
//	{
//		Object req = message.readObject();
//		int waitTime = -1;
//		if (req instanceof ArrayList)
//		{
//			for (Board b : (ArrayList<Board>)req)
//			{
//				serverQueue.enqueue(b);
//			}
//			serverQueue.notifyAll();
//		}
//		else if (req instanceof Double)
//		{
//			waitTime *= ((Double)req).intValue();
//			if (serverQueue != null && serverQueue.size() == 0)
//			{
//				serverQueue.notifyAll();
//				serverQueue = null;
//			}
//		}
//		else if (req instanceof Integer)
//		{
//			serverSolution = serverSolution.intValue() + ((Integer)req).intValue();
//			serverQueue.notifyAll();
//			if (serverQueue != null && serverQueue.size() == 0)
//				serverQueue = null;
//		}
//
//		IbisIdentifier origin = message.origin().ibisIdentifier();
//		String name = message.origin().name();
//		message.finish();
//		Object board;
//		SendPort replyPort = myIbis.createSendPort(replyPortType);
//
//		// connect to the requestor's receive port
//		replyPort.connect(origin, name);
//
//		// create a reply message
//		WriteMessage reply = replyPort.newMessage();
//		if (serverQueue != null && (board = serverQueue.dequeue(1000)) != null) //Wait max 1 second for queue to fill
//			reply.writeObject(board);
//		else
//			reply.writeObject(new Integer(waitTime));
//		reply.finish();
//
//		replyPort.close();
//	}
//}
