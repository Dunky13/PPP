package ida.ipl.bak3;

import ibis.ipl.Ibis;
import ibis.ipl.IbisCapabilities;
import ibis.ipl.IbisCreationFailedException;
import ibis.ipl.IbisFactory;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.PortType;

/**
 * Solver for Rubik's cube puzzle
 */
public class Ida
{

	public final static IbisCapabilities ibisCapabilities = new IbisCapabilities(
		IbisCapabilities.ELECTIONS_STRICT,
		IbisCapabilities.TERMINATION);

	public final static int INIT_VALUE = -1;

	public final static PortType portType = new PortType(
		PortType.COMMUNICATION_RELIABLE,
		PortType.SERIALIZATION_OBJECT_IBIS,
		PortType.RECEIVE_AUTO_UPCALLS,
		PortType.CONNECTION_MANY_TO_ONE,
		PortType.CONNECTION_UPCALLS,
		PortType.SERIALIZATION_OBJECT);

	public final Ibis ibis;

	Ida() throws IbisCreationFailedException
	{
		ibis = IbisFactory.createIbis(ibisCapabilities, null, portType, portType);
	}

	void run(String[] args) throws Exception
	{
		String fileName = null;
		boolean useCache = true;

		for (int i = 0; i < args.length; i++)
		{
			if (args[i].equals("--file"))
			{
				fileName = args[++i];
			}
			else if (args[i].equals("--nocache"))
			{
				useCache = false;
			}
			else
			{
				System.err.println("No such option: " + args[i]);
				ibis.registry().terminate();
				System.exit(1);
			}
		}

		// Elect a master
		IbisIdentifier master = ibis.registry().elect("Master");

		// If I am the master, run master, else run worker
		if (master.equals(ibis.identifier()))
		{
			new Server(this).run(fileName, useCache);
		}
		else
		{
			new Slave(this).run(master, useCache);
		}

		// End ibis
		ibis.end();
	}

	public static void main(String[] arguments)
	{
		try
		{
			new Ida().run(arguments);
		}
		catch (Exception e)
		{
			e.printStackTrace(System.err);
		}
	}
}
