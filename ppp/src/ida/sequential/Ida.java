package ida.sequential;

import java.util.ArrayList;

public class Ida
{

	private static void solve(Board board, boolean useCache)
	{
		BoardCache cache = null;
		if (useCache)
		{
			cache = new BoardCache();
		}

		int bound = board.distance();
		int solutions = 0;

		System.out.print("Try bound ");
		System.out.flush();

		do
		{
			board.setBound(bound);

			System.out.print(bound + " ");
			System.out.flush();

			if (useCache)
			{
				solutions = solutions(board, cache);
			}
			else
			{
				solutions = solutions(board);
			}

			bound++;
		} while (solutions == 0);

		System.out.println("\nresult is " + solutions + " solutions of " + board.bound() + " steps");
		System.out.flush();
	}

	/**
	 * expands this board into all possible positions, and returns the number of
	 * solutions. Will cut off at the bound set in the board.
	 */
	private static int solutions(Board board)
	{

		if (board.distance() == 1)
		{
			return 1;
		}

		//		System.out.println(board.toString(level));

		if (board.distance() > board.bound())
		{
			return 0;
		}

		ArrayList<Board> moves = board.makeMoves();
		int result = 0;

		for (Board newBoard : moves)
		{
			if (newBoard != null)
			{
				result += solutions(newBoard);
			}
		}
		return result;
	}

	/**
	 * expands this board into all possible positions, and returns the number of
	 * solutions. Will cut off at the bound set in the board.
	 */
	private static int solutions(Board board, BoardCache cache)
	{
		if (board.distance() == 1)
		{
			return 1;
		}

		if (board.distance() > board.bound())
		{
			return 0;
		}

		ArrayList<Board> moves = board.makeMoves(cache);
		int result = 0;

		for (Board newBoard : moves)
		{
			if (newBoard != null)
			{
				result += solutions(newBoard, cache);
			}
		}
		cache.put(moves);

		return result;
	}

	public static void main(String[] args)
	{
		String fileName = null;
		boolean cache = true;

		for (int i = 0; i < args.length; i++)
		{
			if (args[i].equals("--file"))
			{
				fileName = args[++i];
			}
			else if (args[i].equals("--nocache"))
			{
				cache = false;
			}
			else
			{
				System.err.println("No such option: " + args[i]);
				System.exit(1);
			}
		}

		Board initialBoard = null;

		if (fileName == null)
		{
			System.err.println("No input file provided.");
			System.exit(1);
		}
		else
		{
			try
			{
				initialBoard = new Board(fileName);
			}
			catch (Exception e)
			{
				System.err.println("could not initialize board from file: " + e);
				System.exit(1);
			}
		}
		System.out.println("Running IDA* sequential, initial board:");
		System.out.println(initialBoard);

		long start = System.currentTimeMillis();
		solve(initialBoard, cache);
		long end = System.currentTimeMillis();

		// NOTE: this is printed to standard error! The rest of the output is
		// constant for each set of parameters. Printing this to standard error
		// makes the output of standard out comparable with "diff"
		System.err.println("ida took " + (end - start) + " milliseconds");
	}
}
