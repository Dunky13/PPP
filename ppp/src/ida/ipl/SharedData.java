package ida.ipl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.ReceivePort;
import ibis.ipl.SendPort;

class SharedData
{
	public static final Object lock = new Object();
	private final Ida parent;
	private final HashMap<IbisIdentifier, SendPort> senders;
	private ReceivePort receiver;
	private final LinkedBlockingQueue<Board> queue;
	private final AtomicInteger solutions;
	private final AtomicInteger minimalQueueSize;
	private final AtomicInteger nodesWaiting;
	private final AtomicInteger currentBound;
	private Board initialBoard;
	private BoardCache cache;

	private volatile BoundStatus bStatus;
	private volatile ProgramStatus pStatus;

	public SharedData(Ida parent)
	{
		this.parent = parent;
		this.senders = new HashMap<IbisIdentifier, SendPort>();
		this.queue = new LinkedBlockingQueue<Board>();
		this.solutions = new AtomicInteger(0);
		this.minimalQueueSize = new AtomicInteger(0);
		this.nodesWaiting = new AtomicInteger(0);
		this.currentBound = new AtomicInteger(0);
		this.bStatus = BoundStatus.TOCHANGE;
		this.pStatus = ProgramStatus.RUNNING;
	}

	void getters()
	{

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

	public boolean useCache()
	{
		return this.cache != null;
	}

	public boolean programFinished()
	{

		boolean progFinished = this.pStatus == ProgramStatus.DONE || this.solutions.get() > 0 && this.boundFinished();
		if (progFinished)
		{
			SharedData.notifyAll(queue);
			SharedData.notifyAll(lock);
			this.pStatus = ProgramStatus.DONE;
		}

		return progFinished;
	}

	private boolean boundFinished()
	{
		boolean bound = queue.isEmpty() && this.bStatus != BoundStatus.CHANGING;
		if (!this.senders.isEmpty()) //If there are slaves connected
			bound = bound && this.nodesWaiting.get() == (this.senders.size() + 1);

		if (bound)
			this.bStatus = BoundStatus.TOCHANGE;
		return bound;
	}

	/**
	 * Pop Board from queue if it is not empty.
	 * 
	 * @return Board
	 */
	public Board getBoard()
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
				b = queue.poll(50, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e)
			{
			}
		} while (b == null && !programFinished());

		return b;
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

	void setters()
	{

	}

	public void setReceiver(ReceivePort receiver)
	{
		this.receiver = receiver;
	}

	public void setInitialBoard(Board initialBoard)
	{
		this.initialBoard = initialBoard;

		queue.clear();

		ArrayList<Board> boards = this.useCache() ? initialBoard.makeMoves(getCache()) : initialBoard.makeMoves();

		addBoards(boards);

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

	private boolean putBoard(Board b)
	{
		try
		{
			queue.put(b);
			return true;
		}
		catch (InterruptedException e)
		{
			return false;
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
			while (!putBoard(b))
			{
			}
		}
		SharedData.notifyAll(queue);

	}

	void calculators()
	{

	}

	public void calculateMinimumQueueSize()
	{
		this.minimalQueueSize.set((this.senders.size() + 1) * 2);
	}

	public int addMoreBoardsToQueue()
	{
		if (queue.size() >= minimalQueueSize.get())
			return 0;
		return (minimalQueueSize.get() - queue.size()) * 2;
	}

	void statics()
	{

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

	public static boolean notifyAll(Object o)
	{
		synchronized (o)
		{
			o.notifyAll();
		}
		return true;
	}

	enum BoundStatus
	{
		CHANGING,
		TOCHANGE,
		CHANGED;
	}

	enum ProgramStatus
	{
		RUNNING,
		DONE
	}
}