import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;

public class Handler extends Thread{

	@SuppressWarnings("unused")
	private Socket client;
	private ObjectInputStream in;
	private ObjectOutputStream out;
	
	private Server server;
	private int clientID;
	
	private int SERVERS_PORT;
	public String IP;
	
	private SharedContent queue;
	
	public int DELIVERY_PORT;
	public DatagramSocket confirmation;
	
	public Handler(Server server, Socket client, int clientID, ObjectOutputStream out) throws IOException {
		this.client = client;
		this.server = server;
		this.clientID = clientID;
		
		SERVERS_PORT = server.SERVERS_PORT;
		DELIVERY_PORT = server.DELIVERY_PORT;
		
		in = new ObjectInputStream(client.getInputStream());
		this.out = out;
		
		queue = new SharedContent(new ArrayList<>());
		confirmation = server.confirmation;
		
		IP = InetAddress.getLocalHost().getHostAddress();
	}
	
	public void run() {
		
		Thread unchoker = new Unchoker(queue, SERVERS_PORT);
		unchoker.start();
		
		Message msg;
		boolean first = true;
		
		while(true) {
			try {
				msg = (Message) in.readObject();
				
				if (msg.type.equals("quit")) {
		           	out.writeObject(new Message("ok"));
		       		out.flush();
		       		
		           	break;
	           }
	           else {
	        	   // assegno scalar clock solo alle write, le read sono eseguite in locale istantaneamente
	        	   if (msg.type.equals("write")) {
	        		   msg.clock = server.getClock();
	        	   }
		           msg.source = IP;
		           msg.clientID = clientID;
		           
		           if (!first)
		        	   msg.hasPrevious = true;
		           else {
		        	   msg.hasPrevious = false;
		        	   first = false;
		           }
		           
		           queue.getQueue().add(msg);
		           new DeliveryService(queue, msg, SERVERS_PORT, confirmation).start();
		           
		           System.out.println("\nMessaggio del client inoltrato al receiver");
	           }
			} catch (ClassNotFoundException | IOException e) {
				;
			}
		}
		
		unchoker.interrupt();
	}
	
}
