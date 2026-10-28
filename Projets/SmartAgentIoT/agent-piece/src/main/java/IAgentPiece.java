
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface distante d'un agent représentant une pièce du bâtiment.
 * Le coordinateur utilise ces méthodes via Java RMI.
 */
public interface IAgentPiece extends Remote {

    double getTemperature() throws RemoteException;

    int getLuminosite() throws RemoteException;

    boolean isOccupee() throws RemoteException;

    void setConsigneTemperature(double temperature) throws RemoteException;

    String getEtatJson() throws RemoteException;
}
