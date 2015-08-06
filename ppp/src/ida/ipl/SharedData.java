package ida.ipl;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
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
	public static final int numberOfBoardsPerSend = 4;
	private volatile BoundStatus bStatus;
	private BoardCache cache;
	private final AtomicInteger currentBound;
	private final LinkedBlockingDeque<Board> deque;
	private Board initialBoard;
	private final AtomicInteger minimalQueueSize;
	private final AtomicInteger nodesWaiting;
	private final Ida parent;

	private volatile ProgramStatus pStatus;
	private ReceivePort receiver;

	private final ConcurrentHashMap<IbisIdentifier, SendPort> senders;

	private final AtomicInteger solutions;

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new ConcurrentHashMap<IbisIdentifier, SendPort>();
		this.deque = new LinkedBlockingDeque<Board>();
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
		for (Board b : boards)
		{
			while (!putBoard(b))
			{
			}
		}
		SharedData.notifyAll(deque);

	}

	public int addMoreBoardsToQueue()
	{
		if (deque.size() >= minimalQueueSize.get())
			return 0;
		return (minimalQueueSize.get() - deque.size()) * SharedData.numberOfBoardsPerSend;
	}

	public void calculateMinimumQueueSize()
	{
		this.minimalQueueSize.set((this.senders.size() + 1) * SharedData.numberOfBoardsPerSend);
	}

	/**
	 * Pop Board from queue if it is not empty.
	 * 
	 * @return Board
	 */
	public Board getBoard(boolean getLast)
	{
		if (programFinished())
			return null;
		if (boundFinished() && this.bStatus == BoundStatus.TOCHANGE)
			incrementBound();
		Board b = null;
		do
		{
			try
			{
				b = getLast ? deque.pollLast(50, TimeUnit.MILLISECONDS) : deque.pollFirst(50, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e)
			{
			}
		} while (b == null && !programFinished());

		return b;
	}

	public ArrayList<Board> getBoards(int amount, boolean getLast)
	{
		ArrayList<Board> boards = new ArrayList<Board>();

		for (int i = 0; i < amount; i++)
		{
			boards.add(getBoard(getLast));
		}
		return boards;
	}

	public BoardCache getCache()
	{
		return cache;
	}

	public AtomicInteger getCurrentBound()
	{
		return currentBound;
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

	public ConcurrentHashMap<IbisIdentifier, SendPort> getSenders()
	{
		return senders;
	}

	//	/**
	//	 * Returns board from queue, if queue is empty wait for queue to be filled
	//	 * again.
	//	 * 
	//	 * Blocking property
	//	 * 
	//	 * @return Board
	//	 * @throws InterruptedException
	//	 */
	//	public Board getBoardAfterWait(boolean getLast)
	//	{
	//		if (programFinished())
	//			return null;
	//		Board b = null;
	//		do
	//		{
	//			if (this.bStatus == BoundStatus.TOCHANGE)
	//				incrementBound();
	//			b = getBoard(getLast);
	//		} while (b == null && (deque.isEmpty() && !programFinished() && SharedData.wait(deque, 50)));
	//		/*
	//		 * If b is not null can return immedeatly
	//		 * Else the solution is not yet found AND the queue is empty - then wait (wait always return true, is notified when something is added to the queue)
	//		 */
	//
	//		return b;
	//	}

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
			SharedData.notifyAll(deque);
			SharedData.notifyAll(lock);
			this.pStatus = ProgramStatus.DONE;
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

		deque.clear();

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

	private boolean putBoard(Board b)
	{
		try
		{
			deque.put(b);
			return true;
		}
		catch (InterruptedException e)
		{
			return false;
		}
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