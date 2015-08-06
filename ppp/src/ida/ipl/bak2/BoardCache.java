package ida.ipl.bak2;

import java.util.ArrayList;

public class BoardCache
{

	public static final int MAX_CACHE_SIZE = 10 * 1024;

	Board[] cache;
	int size;

	public BoardCache()
	{
		size = 0;
		cache = new Board[MAX_CACHE_SIZE];
	}

	public Board get(Board original)
	{
		if (size > 0)
		{
			size--;
			Board result = cache[size];
			result.init(original);
			return result;
		}
		else
		{
			return new Board(original);
		}
	}

	public void put(ArrayList<Board> boards)
	{
		for (Board board : boards)
		{
			if (board == null)
				return;
			if (size >= MAX_CACHE_SIZE)
				return;
			cache[size] = board;
			size++;
		}
	}
}
