package ida.sequential;

import ida.sequential.Board.Position;


public class Car {

	public Position start = null;
	public Position end = null;
	
	public Car() {}
	
	public Car(Car car) {
		start = new Position(car.start);
		end = new Position(car.end);
	}
	
	public void init(Car car) {
		start.init(car.start);
		end.init(car.end);
	}

	public boolean isHorizontal() {
		return start.lin == end.lin;
	}
	
	public void move(int dLin, int dCol) {
		start.lin += dLin;
		start.col += dCol;
		end.lin += dLin;
		end.col +=dCol;
	}
	
	@Override
	public String toString() {
		return "Car [start=" + start + ", end=" + end + ", h=" + isHorizontal() + "]";
	}
}
