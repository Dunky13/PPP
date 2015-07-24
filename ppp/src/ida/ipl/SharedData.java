package ida.ipl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;

class SharedData
{
	private final Ida parent;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private ReceivePort receiver;
	private final ConcurrentLinkedDeque<Board> deque;
	private final AtomicInteger solutions;
	private final AtomicInteger minimalQueueSize;
	private final AtomicInteger nodesWaiting;
	private final AtomicInteger currentBound;
	private Board initialBoard;
	private BoardCache cache;
	private final AtomicBoolean forceBoundStop;

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new HashMap<IbisIdentifier, SendPort>();
		this.deque = new ConcurrentLinkedDeque<Board>();
		this.solutions = new AtomicInteger(0);
		this.minimalQueueSize = new AtomicInteger(0);
		this.nodesWaiting = new AtomicInteger(0);
		this.currentBound = new AtomicInteger(0);
		this.forceBoundStop = new AtomicBoolean(false);
	}

	public Ida getParent()
	{
		return parent;
	}

	public HashMap<IbisIdentifier, SendPort> getSenders()
	{
		return senders;
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

	public AtomicInteger getNodesWaiting()
	{
		return nodesWaiting;
	}

	public int getMinimalQueueSize()
	{
		return minimalQueueSize.get();
	}

	public AtomicInteger getCurrentBound()
	{
		return currentBound;
	}

	public Board getInitialBoard()
	{
		return initialBoard;
	}

	public BoardCache getCache()
	{
		return cache;
	}

	/**
	 * Pop Board from queue if it is not empty.
	 * 
	 * @return Board
	 */
	public synchronized Board getBoard()
	{
		if (!deque.isEmpty())
			return deque.pop();
		return null;
	}

	public Board getWaitingBoard()
	{
		Board b = null;
		do
		{
			b = getBoard();
		} while (b == null && DequeIsEmpty() && !programFinished() && SharedData.wait(deque));
		/*
		 * If b is not null can return immedeatly
		 * Else the solution is not yet found AND the queue is empty - then wait (wait always return true, is notified when something is added to the queue)
		 */

		return b;
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
		if (!this.senders.isEmpty())
			bound = bound && this.nodesWaiting.get() == (this.senders.size() + 1);
		return bound || this.forceBoundStop.get();
	}

	public boolean DequeIsEmpty()
	{
		return this.deque.isEmpty();
	}

	public boolean useCache()
	{
		return this.cache != null;
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

	/**
	 * Increment bound of initialBoard unless solutions are found.
	 */
	public void incrementBound()
	{
		if (!deque.isEmpty())
			return;
		synchronized (this.initialBoard)
		{
			int bound = this.currentBound.incrementAndGet();
			this.initialBoard.setBound(bound);
			System.out.print(" " + bound);
			setInitialBoard(this.initialBoard);
		}

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

	public void calculateMinimumQueueSize()
	{
		this.minimalQueueSize.set((this.senders.size() + 1) * 4);
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

	public void setCurrentBound(int bound)
	{
		this.currentBound.set(bound);
	}

	public void STOP()
	{
		this.forceBoundStop.set(true);
		synchronized (deque)
		{
			while (!deque.isEmpty())
				deque.remove();
		}
	}

}