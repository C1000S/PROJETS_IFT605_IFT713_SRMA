package ca.usherbrooke.ift605.coordinateur;

import ca.usherbrooke.ift605.agent.IAgentPiece;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

/** Faux agent en mémoire : permet de simuler températures, timeout et pannes RMI. */
class AgentFactice implements IAgentPiece {

    double temperature;
    boolean timeout;
    boolean enPanne;
    final List<Double> consignesRecues = new ArrayList<>();

    AgentFactice(double temperature) {
        this.temperature = temperature;
    }

    private void verifierPanne() throws RemoteException {
        if (enPanne) {
            throw new RemoteException("agent hors ligne (panne simulée)");
        }
    }

    @Override public double getTemperature() throws RemoteException { verifierPanne(); return temperature; }
    @Override public int getLuminosite() throws RemoteException { verifierPanne(); return 0; }
    @Override public boolean isOccupee() throws RemoteException { verifierPanne(); return true; }
    @Override public boolean isEnTimeout() throws RemoteException { verifierPanne(); return timeout; }
    @Override public String getEtatJson() throws RemoteException { verifierPanne(); return "{}"; }
    @Override public String consulterVoisin(String piece) throws RemoteException { verifierPanne(); return "{}"; }

    @Override
    public void setConsigneTemperature(double t) throws RemoteException {
        verifierPanne();
        consignesRecues.add(t);
    }
}