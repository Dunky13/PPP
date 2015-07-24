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

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new HashMap<IbisIdentifier, SendPort>();
		this.waitingForWork = new ArrayDeque<IbisIdentifier>();
		this.deque = new ConcurrentLinkedDeque<Board>();// new
		// ArrayDeque<Board>();
		this.solutions = new AtomicInteger(0);
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

	public boolean programFinished()
	{
		if (this.solutions.get() > 0 && this.boundFinished())
			return true;
		return false;
	}

	public boolean boundFinished()
	{
		boolean bound = deque.isEmpty();
		if (this.senders.size() > 0)
		{
			synchronized (waitingForWork)
			{
				bound = bound && this.waitingForWork.size() == this.senders.size();
			}
		}
		return bound;
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

	public boolean useCache()
	{
		return this.cache != null;
	}

	public boolean DequeIsEmpty()
	{
		return this.deque.isEmpty();
	}

	/**
	 * Pop Board from queue if it is not empty.
	 * 
	 * @return Board
	 */
	public Board getBoard()
	{
		Board board = null;
		if (deque.isEmpty()) // Bound finished and no result found
			return null;
		board = deque.pop();
		return board;
	}

	public Board getWaitingBoard()
	{
		Board b = null;
		do
		{
			b = getBoard();
		} while (b == null && !programFinished() && DequeIsEmpty() && SharedData.wait(deque));
		/*
		 * If b is not null can return immedeatly
		 * Else the solution is not yet found AND the queue is empty - then wait (wait always return true, is notified when something is added to the queue)
		 */

		return b;
	}

	/**
	 * Add boards to the queue and notify the waiting threads to start picking
	 * up work.
	 * 
	 * @param boards
	 */
	public void addBoards(ArrayList<Board> boards)
	{
		for (Board b : boards)
		{
			deque.add(b);
		}
		SharedData.notifyAll(deque);
	}

	public static boolean wait(Object o)
	{
		synchronized (o)
		{
			try
			{
				o.wait();
			}
			catch (InterruptedException e)
			{
			}
		}
		return true;
	}

	public static boolean notifyAll(Object o)
	{
		synchronized (o)
		{
			o.notifyAll();
		}
		return true;
	}

	/**
	 * Increment bound of initialBoard unless solutions are found.
	 */
	public void incrementBound()
	{
		if (!deque.isEmpty())
			return;
		synchronized (this.initialBoard)
		{
			int bound = this.initialBoard.bound() + 1;
			this.initialBoard.setBound(bound);
			System.out.print(" " + bound);
			setInitialBoard(this.initialBoard);
		}

	}

}