import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

public class Receiver extends Thread {
	
	private InetAddress group;
	private int SERVERS_PORT;
	private MulticastSocket multicast;
	public String IP;
	
	private List<Message> queue;
	private List<Message> ackList;
	private Hashtable<Integer, Integer> storage;
	private List<Message> receivedMessages;
	
	private Set<String> servers;
	
	public int DELIVERY_PORT = 8503;

	public Receiver(int port) throws IOException {
		
		SERVERS_PORT = port;
		
		group = InetAddress.getByName("224.0.5.1");
		multicast = new MulticastSocket(SERVERS_PORT);
		multicast.joinGroup(group);		
		IP = InetAddress.getLocalHost().getHostAddress();
		queue = new ArrayList<>();
		ackList = new ArrayList<>();
		storage = new Hashtable<>();
		receivedMessages = new ArrayList<>();
		servers = new HashSet<>();
		servers.add("192.168.1.176");
		servers.add("192.168.1.221");
		
		new AlivenessSender().start();
		new AlivenessChecker(SERVERS_PORT).start();
	}
	
	public void sendUDP(Message msg, int port) throws IOException, InterruptedException {
		
		DatagramSocket socket = new DatagramSocket();
		ByteArrayOutputStream baos = new ByteArrayOutputStream(6400);
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		
		oos.writeObject(msg);
		byte[] data_r = baos.toByteArray();
		InetAddress addr = InetAddress.getByName("localhost");
		DatagramPacket packet = new DatagramPacket(data_r, data_r.length, addr, port);
		
		socket.send(packet);
		Thread.sleep(500);
		socket.close();
	}
	
	public void sendUDPtoServer(Message msg, int port, String IP) throws IOException, InterruptedException {
		
		DatagramSocket socket = new DatagramSocket();
		ByteArrayOutputStream baos = new ByteArrayOutputStream(6400);
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		
		oos.writeObject(msg);
		byte[] data_r = baos.toByteArray();
		InetAddress addr = InetAddress.getByName(IP);
		DatagramPacket packet = new DatagramPacket(data_r, data_r.length, addr, port);
		
		socket.send(packet);
		Thread.sleep(500);
		socket.close();
	}
	
	public void sendMulticast(Message msg, boolean isAck) throws IOException, InterruptedException {
		
		if (isAck) {
			msg.isAck = true;
			msg.ackSource = IP;
		}
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream(6400);
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(msg);
		byte[] data = baos.toByteArray();
		
		DatagramPacket packet = new DatagramPacket(data, data.length, group, SERVERS_PORT);
		
		multicast.send(packet);
		Thread.sleep(500);
	}
	
	public Message receiveMessage(byte[] buff) throws IOException, ClassNotFoundException {
		DatagramPacket recv = new DatagramPacket(buff, buff.length);
		multicast.receive(recv);
		
		ByteArrayInputStream bais = new ByteArrayInputStream(buff);
		ObjectInputStream ois = new ObjectInputStream(bais);
		
		Message mess = (Message) ois.readObject();
		
		return mess;
	}
	
	private List<Message> extractAckSublist() {
		
		int finalIdx = 0;
		
		for(Message m : ackList) {
			if (m.equalsLite(queue.get(0))) {
				finalIdx++;
			}
			else {
				break;
			}
		}
		
		return ackList.subList(0, finalIdx);
		
	}
	
	private List<Message> extractOkSublist() {
		
		int finalIdx = 0;
		
		if (queue.size() != 0) {
		
			for(Message m : receivedMessages) {
				if (m.equalsLite(queue.get(0))) {
					finalIdx++;
				}
				else {
					break;
				}
			}
		
		}
		
		return receivedMessages.subList(0, finalIdx);
		
	}
	
	private List<Message> extractOkSublist(Message msg) {
		
		int finalIdx = 0;
		
		for(Message m : receivedMessages) {
			if (m.equalsLite(msg)) {
				finalIdx++;
			}
			else {
				break;
			}
		}
		
		return receivedMessages.subList(0, finalIdx);
		
	}
	
	private boolean isFullyAcknowledged() {
		
		List<Message> acks = extractAckSublist();
		
		if (acks.size() == servers.size()) {
			return true;
		}
		else {
			return false;
		}
	}
	
	private boolean isFullyOk() {
		
		List<Message> ok = extractOkSublist();
		
		if (ok.size() == servers.size()) {
			return true;
		}
		else {
			return false;
		}
	}
	
	private void manageRetransmissions() throws IOException, InterruptedException {
		
		List<Message> acks = extractAckSublist();
		@SuppressWarnings("unchecked")
		HashSet<String> IPs = (HashSet<String>) ((HashSet<String>) servers).clone();
		
		for (Message m : acks) {
			if (IPs.contains(m.ackSource)) {
				// dopo aver creato una copia degli IP dei server noti, rimuovo gli IP che hanno gi� fornito l'ack
				IPs.remove(m.ackSource);
			}
		}
		
		//agli IP rimanenti invio la richiesta di ritrasmissione
		Message msg = queue.get(0);
		msg.isAck = true;
		msg.ackSource = this.IP;
		msg.isRetransmit = true;
		
		for (String IP : IPs) {
			sendUDPtoServer(msg, SERVERS_PORT, IP);
		}
	}
	
	private boolean isAlreadyPresent(Message msg) {
		boolean flag = false;
		
		for(Message m : queue) {
			if (msg.equals(m)) {
				flag = true;
				break;
			}
		}
		
		return flag;
	}
	
	private boolean isAlreadyPresent(Message msg, String mode) {
		boolean flag = false;
		
		if (mode.equals("list")) {
			for(Message m : ackList) {
				if (msg.equals(m, mode)) {
					flag = true;
					break;
				}
			}
		}
		else if (mode.equals("ok")) {
			for(Message m : receivedMessages) {
				if (msg.equals(m, mode)) {
					flag = true;
					break;
				}
			}
		}
		
		return flag;
	}
	
	private void printQueue() {
		
		System.out.println("Coda dei messaggi: \n");
		
		for (Message m : queue) {
			m.print();
		}
		
		System.out.println("\n");
	}
	
	private void printOk() {
		
		System.out.println("Coda dei messaggi di ok: \n");
		
		for (Message m : receivedMessages) {
			m.print();
		}
		
		System.out.println("\n");
	}
	
	private void printList() {
		
		System.out.println("Lista degli ack: \n");
		
		for(Message m : ackList) {
			m.print();
		}
		
		System.out.println("\n");
	}
	
	private boolean valid() {
		if (queue.size() != 0) {
			if (queue.get(0).source.equals(IP)) {
				return true;
			}
			else {
				return false;
			}
		}
		
		return false;
	}
	
	private void clean(List<Message> executionList) {
		
		try {
			for (Message m : executionList) {
				if (queue.size() != 0) {
					for (Message msg : queue) {
						if (m.equalsLite(msg)) {
							queue.remove(msg);
						}
					}
				}
				
				if (ackList.size() != 0) {
					for (Message ack : ackList) {
						if (m.equalsLite(ack)) {
							ackList.remove(ack);
						}
					}
				}
				
				if (receivedMessages.size() != 0) {
					for (Message ok : receivedMessages) {
						if (m.equalsLite(ok)) {
							receivedMessages.remove(ok);
						}
					}
				}
			}
		}
		catch (Exception e) {
			;
		}
	}
	
	private int count(Message msg) {
		
		int counter = 0;
		
		for (Message m : receivedMessages) {
			if (m.equalsLite(msg)){
				counter++;
			}
		}
		
		return counter;
	}
	
	private Message find(Message msg) {
		for (Message m : queue) {
			if (m.equalsLite(msg)) {
				return m;
			}
		}
		
		// non succede mai
		return null;
	}
	
	private void ackForwarding() throws IOException, InterruptedException {
		
		int number;
		
		for (int i=0; i< receivedMessages.size(); i++) {
			number = count(receivedMessages.get(i));
			
			if (number == servers.size()) {
				// richiedo gli ack
				Message request = new Message(find(receivedMessages.get(i)));
				request.type = "send";
				
				sendMulticast(request, true);
				
				//aggiorno il contatore per evitare di mandare lo stesso messaggio troppe volte
				i += servers.size();
			}
		}
	}
	
	public void run() {
		
		byte[] buff = new byte[8192];
		boolean busy = false;
		long cycle = 0;
		
		// lista contenente tutti i messaggi eseguiti
		List<Message> executionList = new ArrayList<>();
		
		while(true) {
			
			try {
				// ricevo il messaggio
				Message mess = receiveMessage(buff);
				
				//mess.print();
				
				if (!mess.type.equals("unlock") && !mess.type.equals("ok") && !mess.type.equals("send")) {
					// gestione messaggi normali
					if(!mess.isAck) {
						// il messaggio non � un ack
						
						// il messaggio proviene dal client, che lo ha sottomesso al server
						if (!isAlreadyPresent(mess) && mess.source.equals(IP)) {
							// invio conferma ricezione da parte del server all'handler
							sendUDP(mess, DELIVERY_PORT);
							mess.cycle = cycle;
							queue.add(mess);
							
							// ordino la coda
							Collections.sort(queue, (m1, m2) -> m1.source.hashCode() - m2.source.hashCode());
							Collections.sort(queue, (m1, m2) -> m1.clock - m2.clock);
							
							// invio il mio messaggio agli altri server
							sendMulticast(mess, false);
							System.out.println("Messaggio inviato dal client ricevuto e inserito in coda. Inoltrati i messaggi agli altri server");
						}
						else {
							if(!isAlreadyPresent(mess)) {
								// il messaggio � stato inviato da un altro server e io devo inserirlo in coda e mandare un messaggio di ok al mittente
								mess.cycle = cycle;
								queue.add(mess);
								
								// ordino la coda
								Collections.sort(queue, (m1, m2) -> m1.source.hashCode() - m2.source.hashCode());
								Collections.sort(queue, (m1, m2) -> m1.clock - m2.clock);
								
								// ordino la lista
								Collections.sort(receivedMessages, (m1, m2) -> m1.source.hashCode() - m2.source.hashCode());
								Collections.sort(receivedMessages, (m1, m2) -> m1.clock - m2.clock);
								
								System.out.println("Ricevuto messaggio proveniente da " + mess.source + ". Inserimento in coda avvenuto.");
							}
							
							Message okMsg = new Message(mess);
								
							okMsg.type = "ok";
							okMsg.ackSource = IP;
							
							// invio il mio messaggio di ok
							sendMulticast(okMsg, false);
							
							if(!mess.source.equals(IP)) {
								System.out.println("Messaggio di ok mandato a " + mess.source + " da " + IP);
							}
						}
					}
					else {
						// il messaggio � un ack
						
						// eseguo la ritrasmissione del mio ack per il messaggio inviatomi
						if (mess.isRetransmit) {
							// invio in multicast il mio ack
							String destination = mess.ackSource;
							
							mess.type = "ack";
							mess.isRetransmit = false;
							mess.ackSource = IP;
							
							sendUDPtoServer(mess, SERVERS_PORT, destination);
							System.out.println("Ack ritrasmesso a " + destination);
						}
						else {
							// controllo duplicati
							if (!isAlreadyPresent(mess, "list")) {
								// aggiungo l'ack alla lista delgi ack ricevuti
								Message m = new Message(mess);
								
								ackList.add(m);
								
								// ordino la lista
								Collections.sort(ackList, (m1, m2) -> m1.source.hashCode() - m2.source.hashCode());
								Collections.sort(ackList, (m1, m2) -> m1.clock - m2.clock);
								
								System.out.println("Ack da " + mess.ackSource + " ricevuto e inserito in lista");
							}
						}
					}
				}
				else {
					// gestione messaggi di unlock e di ok
					if (mess.type.equals("ok")) {
						if(!isAlreadyPresent(mess, "ok")){
							// gestisco il fatto che gli altri server abbiano ricevuto il mio messaggio e lo abbiano aggiunto in coda
							receivedMessages.add(mess);
							
							// ordino la lista
							Collections.sort(receivedMessages, (m1, m2) -> m1.source.hashCode() - m2.source.hashCode());
							Collections.sort(receivedMessages, (m1, m2) -> m1.clock - m2.clock);
							
							System.out.println("Ricevuto messaggio di ok da parte di " + mess.ackSource);
						}
					}
					else if(mess.type.equals("send")) {
						// invio in multicast il mio ack
						
						mess.type = "ack";
						
						sendMulticast(mess, true);
						System.out.println("Inviato ack in multicast da " + IP);
					}
					else {
						// gestione messaggi di unlock
						cycle++;
						
						// se il messaggio � in coda da pi� di due cicli di unlock chiedo la ritrasmissione totale
						if (queue.size() != 0) {
							if (cycle >= queue.get(0).cycle + 2) {
								
								// ci sono due possibilit�: 1) ho ricevuto i messaggi di ok ma ho perso un ack 2) non ho tutti i messaggi di ok
								// nel caso 1) mi basta richiedere l'invio di un ack
								// nel caso 2) devo reinviare tutto
								
								// significa che ho perso un ack, quindi mi basta inviare un messaggio di send specificando il retransmit
								if (isFullyOk()) {
									// chiedo ritrasmissione solo ai server mancanti
									manageRetransmissions();
									busy = true;
									
									System.out.println("E' stata richiesta la ritrasmissione di un messaggio di ack");
								}
								else {
									// devo ritrasmettere tutto
									Message request = new Message(queue.get(0));
									request.isAck = false;
									
									sendMulticast(request, false);
									//manageRetransmissionsOk();
									busy = false;
									
									System.out.println("E' stata richiesta la ritrasmissione di un messaggio di ok");
								}
								
								// aggiorno il cycle del primo elemento in coda
								queue.get(0).cycle = cycle;
							}
						}
					}
				}
				
				// gestione dell'invio degli ack
				/*if (isFullyOk() && !busy && valid()) {
					// invio il mio ack
					Message request = new Message(queue.get(0));
					request.type = "send";
					
					sendMulticast(request, true);
					busy = true;
				}*/
				
				ackForwarding();
				
				// mi assicuro che eventuali messaggi ricevuti con estremo ritardo non vadano a sporcare le code di esecuzione
				clean(executionList);
				
				// controllo se c'� da eseguire qualcosa
				if (isFullyAcknowledged()) {
					while (isFullyAcknowledged()) {
						Message inExecution = queue.get(0);
						executionList.add(inExecution);
						
						// cancello gli ok di conferma dei server dato che il messaggio � arrivato allo stadio finale
						receivedMessages.removeAll(extractOkSublist(inExecution));
						
						if (inExecution.type.equals("write")) {
							storage.put(inExecution.id, inExecution.value);
						}
						
						// rimuovo il messaggio eseguito dalla coda, cancello i suoi ack e lo aggiungo alla lista dei messaggi eseguiti
						ackList.removeAll(extractAckSublist());
						queue = queue.subList(1, queue.size());
						
						System.out.println("Messaggio eseguito");
						System.out.println(storage.toString());
						
						// log per confronto finale
						BufferedWriter bw = new BufferedWriter(new FileWriter("controllo.txt", true));
						bw.write(storage.toString());
						bw.newLine();
						bw.flush();
						bw.close();
					}
					
					busy = false;
				}
				
				if (queue.size() != 0) {
					printQueue();
					printList();
					printOk();
				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}