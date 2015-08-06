package ida.ipl.bak2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;

class SharedData
{
	enum BoundStatus
	{
		CHANGED,
		CHANGING,
		TOCHANGE;
	}
	enum ProgramStatus
	{
		DONE,
		RUNNING
	}
	public static final Object lock = new Object();
	private volatile BoundStatus bStatus;
	private BoardCache cache;
	private final AtomicInteger currentBound;
	private final ConcurrentLinkedDeque<Board> deque;
	private Board initialBoard;
	private final AtomicInteger minimalQueueSize;
	private final AtomicInteger nodesWaiting;
	private final Ida parent;

	private volatile ProgramStatus pStatus;
	private ReceivePort receiver;

	private final HashMap<IbisIdentifier, SendPort> senders;

	private final AtomicInteger solutions;

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new HashMap<IbisIdentifier, SendPort>();
		this.deque = new ConcurrentLinkedDeque<Board>();
		this.solutions = new AtomicInteger(0);
		this.minimalQueueSize = new AtomicInteger(0);
		this.nodesWaiting = new AtomicInteger(0);
		this.currentBound = new AtomicInteger(0);
		this.bStatus = BoundStatus.TOCHANGE;
		this.pStatus = ProgramStatus.RUNNING;
	}

	/**
	 * Add boards to the queue and notify the waiting threads to start picking
	 * up work.
	 * 
	 * @param boards
	 */
	public void addBoards(ArrayList<Board> boards)
	{
		synchronized (deque)
		{
			for (Board b : boards)
			{
				deque.add(b);
			}
			SharedData.notifyAll(deque);
		}
	}

	public int addMoreBoardsToQueue()
	{
		if (deque.size() >= minimalQueueSize.get())
			return 0;
		return (minimalQueueSize.get() - deque.size()) * 2;
	}

	public void calculateMinimumQueueSize()
	{
		this.minimalQueueSize.set((this.senders.size() + 1) * 2);
	}

	/**
	 * Pop Board from queue if it is not empty.
	 * 
	 * @return Board
	 */
	public Board getBoard(boolean getLast)
	{
		synchronized (deque)
		{
			if (deque.isEmpty())
				return null;
			if (getLast)
				return deque.removeLast();
			return deque.pop();
		}
	}

	/**
	 * Returns board from queue, if queue is empty wait for queue to be filled
	 * again.
	 * 
	 * Blocking property
	 * 
	 * @return Board
	 * @throws InterruptedException
	 */
	public Board getBoardAfterWait(boolean getLast)
	{
		if (programFinished())
			return null;
		Board b = null;
		do
		{
			if (this.bStatus == BoundStatus.TOCHANGE)
				incrementBound();
			b = getBoard(getLast);
		} while (b == null && (deque.isEmpty() && !programFinished() && SharedData.wait(deque, 50)));
		/*
		 * If b is not null can return immedeatly
		 * Else the solution is not yet found AND the queue is empty - then wait (wait always return true, is notified when something is added to the queue)
		 */

		return b;
	}

	public BoardCache getCache()
	{
		return cache;
	}

	public AtomicInteger getCurrentBound()
	{
		return currentBound;
	}

	public ConcurrentLinkedDeque<Board> getDeque()
	{
		return deque;
	}

	public Board getInitialBoard()
	{
		return initialBoard;
	}

	public int getMinimalQueueSize()
	{
		return minimalQueueSize.get();
	}

	public AtomicInteger getNodesWaiting()
	{
		return nodesWaiting;
	}

	public Ida getParent()
	{
		return parent;
	}

	public ReceivePort getReceiver()
	{
		return receiver;
	}

	public HashMap<IbisIdentifier, SendPort> getSenders()
	{
		return senders;
	}

	public AtomicInteger getSolutions()
	{
		return solutions;
	}

	/**
	 * Increment bound of initialBoard unless solutions are found.
	 */
	public void incrementBound()
	{
		if (this.pStatus == ProgramStatus.DONE)
			return;
		this.bStatus = BoundStatus.CHANGING;
		synchronized (this.initialBoard)
		{
			int bound = this.currentBound.incrementAndGet();
			this.initialBoard.setBound(bound);
			System.out.print(" " + bound);
			setInitialBoard(this.initialBoard);
			this.bStatus = BoundStatus.CHANGED;
		}

	}

	public boolean programFinished()
	{

		boolean progFinished = this.pStatus == ProgramStatus.DONE || this.solutions.get() > 0 && this.boundFinished();
		if (progFinished)
		{
			this.pStatus = ProgramStatus.DONE;
			SharedData.notifyAll(deque);
			SharedData.notifyAll(lock);
		}

		return progFinished;
	}

	public void setCache(BoardCache cache)
	{
		this.cache = cache;
	}

	public void setInitialBoard(Board initialBoard)
	{
		this.initialBoard = initialBoard;

		while (!deque.isEmpty())
			deque.remove();

		ArrayList<Board> boards = this.useCache() ? initialBoard.makeMoves(getCache()) : initialBoard.makeMoves();

		addBoards(boards);

	}

	public void setReceiver(ReceivePort receiver)
	{
		this.receiver = receiver;
	}

	public boolean useCache()
	{
		return this.cache != null;
	}

	void calculators()
	{

	}

	void getters()
	{

	}

	void setters()
	{

	}

	void statics()
	{

	}

	private boolean boundFinished()
	{
		boolean bound = deque.isEmpty() && this.bStatus != BoundStatus.CHANGING;
		if (!this.senders.isEmpty()) //If there are slaves connected
			bound = bound && this.nodesWaiting.get() == (this.senders.size() + 1);

		if (bound)
			this.bStatus = BoundStatus.TOCHANGE;
		return bound;
	}

	public static boolean notifyAll(Object o)
	{
		synchronized (o)
		{
			o.notifyAll();
		}
		return true;
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

	public static boolean wait(Object o, Integer i)
	{
		synchronized (o)
		{
			try
			{
				if (i == null)
					o.wait();
				else
					o.wait(i.longValue());
			}
			catch (InterruptedException e)
			{
			}
		}
		return true;
	}
}