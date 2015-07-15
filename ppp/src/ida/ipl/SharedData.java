package ida.ipl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;

class SharedData
{
	private final Ida parent;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private final Deque<IbisIdentifier> waitingForWork;
	private ReceivePort receiver;
	private final ConcurrentLinkedDeque<Board> deque;
	private final AtomicInteger solutions;
	private Board initialBoard;
	private BoardCache cache;
	private boolean finished;

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new HashMap<IbisIdentifier, SendPort>();
		this.waitingForWork = new ArrayDeque<IbisIdentifier>();
		this.deque = new ConcurrentLinkedDeque<Board>();//new ArrayDeque<Board>();
		this.solutions = new AtomicInteger(0);
		this.finished = false;
	}

	public Ida getParent()
	{
		return parent;
	}

	public HashMap<IbisIdentifier, SendPort> getSenders()
	{
		return senders;
	}

	public Deque<IbisIdentifier> getWaitingForWork()
	{
		return waitingForWork;
	}

	public ReceivePort getReceiver()
	{
		return receiver;
	}

	public ConcurrentLinkedDeque<Board> getDeque()
	{
		return deque;
	}

	public AtomicInteger getSolutions()
	{
		return solutions;
	}

	public Board getInitialBoard()
	{
		return initialBoard;
	}

	public BoardCache getCache()
	{
		return cache;
	}

	public boolean isFinished()
	{
		return finished;
	}

	public void setReceiver(ReceivePort receiver)
	{
		this.receiver = receiver;
	}

	public void setInitialBoard(Board initialBoard)
	{
		this.initialBoard = initialBoard;

		while (!deque.isEmpty())
			deque.remove();

		ArrayList<Board> boards = this.useCache() ? initialBoard.makeMoves(getCache()) : initialBoard.makeMoves();

		for (Board b : boards)
		{
			deque.addFirst(b);
		}
	}

	public void setCache(BoardCache cache)
	{
		this.cache = cache;
	}

	public void setFinished(boolean finished)
	{
		this.finished = finished;
	}

	public boolean useCache()
	{
		return this.cache != null;
	}
}