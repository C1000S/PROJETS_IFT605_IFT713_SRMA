import java.rmi.registry.*;
import ca.usherbrooke.ift605.agent.*;
var a = (IAgentPiece) LocateRegistry.getRegistry("127.0.0.1", 2199).lookup("agent-salleA");
System.out.println("RESULTAT: " + a.consulterVoisin("salleB"));
/exit
