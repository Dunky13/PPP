package ida.ipl;

import java.io.FileInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Scanner;

/**
 * Class representing a particular state of the Rush Hour game.
 */
public class Board implements Serializable
{
	/**
	 * Class representing a position (line, column) on the board
	 */
	public static final class Position implements Serializable
	{
		/**
		 * 
		 */
		private static final long serialVersionUID = 1509529483481639045L;

		int col; // column

		int lin; // line

		public Position(int lin, int col)
		{
			this.lin = lin;
			this.col = col;
		}
		public Position(Position pos)
		{
			this.lin = pos.lin;
			this.col = pos.col;
		}

		public void init(Position pos)
		{
			lin = pos.lin;
			col = pos.col;
		}

		@Override
		public String toString()
		{
			return "Position [lin=" + lin + ", col=" + col + "]";
		}
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = -7163026043346132515L;

	/*
	 * array with one element for each position on the board. element (line,column) on
	 * the board is (BOARD_SIZE * line) + column in this array ideally this would be
	 * char[BOARD_SIZE][BOARD_SIZE], but this makes creating a new board with the copy
	 * constructor (which we do _a_lot_) too expensive.
	 */
	private char[] board;

	/* the game is played on a 8x8 board */
	private final int BOARD_SIZE = 8;

	private int bound;

	private Car[] cars;

	private int distance;

	/*
	 * the exit of the parking
	 */
	private Position exit;

	private Car redCar;

	/** copy constructor */
	public Board(Board origBoard)
	{
		board = new char[BOARD_SIZE * BOARD_SIZE];
		System.arraycopy(origBoard.board, 0, board, 0, BOARD_SIZE * BOARD_SIZE);

		cars = new Car[origBoard.cars.length];
		for (int i = 0; i < origBoard.cars.length; i++)
		{
			cars[i] = new Car(origBoard.cars[i]);
			if (origBoard.cars[i] == origBoard.redCar)
			{
				redCar = cars[i];
			}
		}

		exit = new Position(origBoard.exit);

		distance = origBoard.distance;
		bound = origBoard.bound;
	}

	/*
	 * read the initial configuration of the board from a file
	 * 
	 * ATTENTION: this method doesn't check if the file contains a valid
	 * board, so please double-check that the boards you provide as input
	 * are valid
	 */
	public Board(String fileName) throws Exception
	{
		Scanner scanner = new Scanner(new FileInputStream(fileName));

		int carsCount = 0;

		board = new char[BOARD_SIZE * BOARD_SIZE];

		/* parse the board */
		for (int i = 0; i < BOARD_SIZE; i++)
		{
			for (int j = 0; j < BOARD_SIZE; j++)
			{
				setBoardValue(scanner.next(".").charAt(0), i, j);

				if (getBoardValue(i, j) == '*')
				{
					exit = new Position(i, j);
				}
				if (getBoardValue(i, j) == '<' || getBoardValue(i, j) == '^')
				{
					carsCount++;
				}
			}
		}

		/* allocate array for carsCount cars plus red car */
		cars = new Car[carsCount + 1];
		Car currentCar = null;
		Car redCar = new Car();
		int carIdx = 0;

		/* parse the horizontal cars */
		for (int i = 0; i < BOARD_SIZE; i++)
		{
			for (int j = 0; j < BOARD_SIZE; j++)
			{
				if (getBoardValue(i, j) == '<')
				{
					currentCar = new Car();
					currentCar.start = new Position(i, j);
				}
				if (getBoardValue(i, j) == '>')
				{
					currentCar.end = new Position(i, j);
					cars[carIdx++] = currentCar;
				}
				if (getBoardValue(i, j) == '?')
				{
					if (redCar.start == null)
					{
						redCar.start = new Position(i, j);
					}
					else
					{
						redCar.end = new Position(i, j);
					}
				}
			}
		}
		/* parse the vertical cars */
		for (int j = 0; j < BOARD_SIZE; j++)
		{
			for (int i = 0; i < BOARD_SIZE; i++)
			{
				if (getBoardValue(i, j) == '^')
				{
					currentCar = new Car();
					currentCar.start = new Position(i, j);
				}
				if (getBoardValue(i, j) == 'v')
				{
					currentCar.end = new Position(i, j);
					cars[carIdx++] = currentCar;
				}
			}
		}

		cars[carIdx++] = redCar;
		this.redCar = redCar;

		distance = calculateBoardDistance();

		scanner.close();
	}

	public int bound()
	{
		return bound;
	}

	public int distance()
	{
		return distance;
	}

	public char getBoardValue(int lin, int col)
	{
		return board[lin * BOARD_SIZE + col];
	}

	public void init(Board origBoard)
	{
		System.arraycopy(origBoard.board, 0, board, 0, BOARD_SIZE * BOARD_SIZE);

		for (int i = 0; i < origBoard.cars.length; i++)
		{
			cars[i].init(origBoard.cars[i]);
			if (origBoard.cars[i] == origBoard.redCar)
			{
				redCar = cars[i];
			}
		}

		exit.init(origBoard.exit);

		distance = origBoard.distance;
		bound = origBoard.bound;
	}

	/**
	 * Make all possible moves with this board position. As an optimization,
	 * does not "undo" the move which created this board. Elements in the
	 * returned array may be "null".
	 */
	public ArrayList<Board> makeMoves()
	{
		ArrayList<Board> moves = new ArrayList<Board>();

		for (int i = 0; i < cars.length; i++)
		{
			Car car = cars[i];

			if (car.isHorizontal())
			{
				for (int j = 1; getBoardValue(car.start.lin, car.start.col - j) == '.'; j++)
				{
					Board newBoard = new Board(this);
					newBoard.moveCar(i, 0, -j);
					moves.add(newBoard);
				}
				for (int j = 1; getBoardValue(car.end.lin, car.end.col + j) == '.'; j++)
				{
					Board newBoard = new Board(this);
					newBoard.moveCar(i, 0, j);
					moves.add(newBoard);
				}
			}
			else
			{ // car is vertical oriented
				for (int j = 1; getBoardValue(car.start.lin - j, car.start.col) == '.'; j++)
				{
					Board newBoard = new Board(this);
					newBoard.moveCar(i, -j, 0);
					moves.add(newBoard);
				}
				for (int j = 1; getBoardValue(car.end.lin + j, car.end.col) == '.'; j++)
				{
					Board newBoard = new Board(this);
					newBoard.moveCar(i, j, 0);
					moves.add(newBoard);
				}
			}
		}

		return moves;
	}

	/**
	 * Make all possible moves with this board position. As an optimization,
	 * does not "undo" the move which created this board. Elements in the
	 * returned array may be "null".
	 */
	public ArrayList<Board> makeMoves(BoardCache cache)
	{
		ArrayList<Board> moves = new ArrayList<Board>();

		for (int i = 0; i < cars.length; i++)
		{
			Car car = cars[i];

			if (car.isHorizontal())
			{
				for (int j = 1; getBoardValue(car.start.lin, car.start.col - j) == '.'; j++)
				{
					Board newBoard = cache.get(this);
					newBoard.moveCar(i, 0, -j);
					moves.add(newBoard);
				}
				for (int j = 1; getBoardValue(car.end.lin, car.end.col + j) == '.'; j++)
				{
					Board newBoard = cache.get(this);
					newBoard.moveCar(i, 0, j);
					moves.add(newBoard);
				}
			}
			else
			{ // car is vertical oriented
				for (int j = 1; getBoardValue(car.start.lin - j, car.start.col) == '.'; j++)
				{
					Board newBoard = cache.get(this);
					newBoard.moveCar(i, -j, 0);
					moves.add(newBoard);
				}
				for (int j = 1; getBoardValue(car.end.lin + j, car.end.col) == '.'; j++)
				{
					Board newBoard = cache.get(this);
					newBoard.moveCar(i, j, 0);
					moves.add(newBoard);
				}
			}
		}

		return moves;
	}

	/*
	 * moves a car on the board
	 */
	public void moveCar(int carIdx, int dLin, int dCol)
	{
		Car car = cars[carIdx];

		if (car.isHorizontal())
		{
			/* remove car from board */
			for (int j = car.start.col; j <= car.end.col; j++)
			{
				setBoardValue('.', car.start.lin, j);
			}

			/* move car */
			car.move(dLin, dCol);

			/* put car on board in the new position */
			setBoardValue(car == redCar ? '?' : '<', car.start.lin, car.start.col);
			setBoardValue(car == redCar ? '?' : '>', car.end.lin, car.end.col);
			for (int j = car.start.col + 1; j < car.end.col; j++)
			{
				setBoardValue(car == redCar ? '?' : '-', car.start.lin, j);
			}
		}
		else
		{
			/* remove car from board */
			for (int i = car.start.lin; i <= car.end.lin; i++)
			{
				setBoardValue('.', i, car.start.col);
			}

			/* move car */
			car.move(dLin, dCol);

			/* put car on board in the new position */
			setBoardValue(car == redCar ? '?' : '^', car.start.lin, car.start.col);
			setBoardValue(car == redCar ? '?' : 'v', car.end.lin, car.end.col);
			for (int i = car.start.lin + 1; i < car.end.lin; i++)
			{
				setBoardValue(car == redCar ? '?' : '|', i, car.start.col);
			}
		}

		distance = calculateBoardDistance();
		bound--;
	}

	public void setBoardValue(char symbol, int lin, int col)
	{
		board[lin * BOARD_SIZE + col] = symbol;
	}

	public void setBound(int bound)
	{
		this.bound = bound;
	}

	public String toString()
	{
		String result = "";
		for (int y = 0; y < BOARD_SIZE; y++)
		{
			for (int x = 0; x < BOARD_SIZE; x++)
			{
				result += getBoardValue(y, x) + " ";
			}
			result += "\n";
		}
		return result;
	}

	/** for debug purposes */
	public String toString(int prefixTabs)
	{
		String result = "";
		String prefix = "";

		for (int i = 0; i < prefixTabs; i++)
		{
			prefix += "\t";
		}
		result += prefix;

		for (int y = 0; y < BOARD_SIZE; y++)
		{
			for (int x = 0; x < BOARD_SIZE; x++)
			{
				result += getBoardValue(y, x) + " ";
			}
			result += "\n" + prefix;
		}
		return result;
	}

	/**
	 * estimates the distance to the goal
	 */
	private int calculateBoardDistance()
	{
		int result = 0;

		int lin = exit.lin;
		int col = exit.col;

		if (lin == 0)
		{
			while (getBoardValue(lin, col) != '?')
			{
				if (getBoardValue(lin, col) != '.')
				{
					result++;
				}
				lin++;
			}
		}
		else if (lin == BOARD_SIZE - 1)
		{
			while (getBoardValue(lin, col) != '?')
			{
				if (getBoardValue(lin, col) != '.')
				{
					result++;
				}
				lin--;
			}
		}
		else if (col == 0)
		{
			while (getBoardValue(lin, col) != '?')
			{
				if (getBoardValue(lin, col) != '.')
				{
					result++;
				}
				col++;
			}
		}
		else if (col == BOARD_SIZE - 1)
		{
			while (getBoardValue(lin, col) != '?')
			{
				if (getBoardValue(lin, col) != '.')
				{
					result++;
				}
				col--;
			}
		}

		return result;
	}
}
