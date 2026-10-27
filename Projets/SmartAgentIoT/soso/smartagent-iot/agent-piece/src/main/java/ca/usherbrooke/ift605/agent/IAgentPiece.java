package ca.usherbrooke.ift605.agent;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface distante (Java RMI) exposée par chaque agent de pièce.
 * <p>
 * Règles RMI à retenir :
 * <ul>
 *   <li>l'interface étend {@link Remote} ;</li>
 *   <li>chaque méthode déclare {@link RemoteException} (panne réseau, agent arrêté...) ;</li>
 *   <li>les paramètres et retours doivent être sérialisables (ici : types primitifs et String).</li>
 * </ul>
 */
public interface IAgentPiece extends Remote {

    /** Dernière température reçue en °C ({@code NaN} si aucune mesure reçue). */
    double getTemperature() throws RemoteException;

    /** Dernière luminosité reçue en lux. */
    int getLuminosite() throws RemoteException;

    /** Dernier état d'occupation reçu. */
    boolean isOccupee() throws RemoteException;

    /** Publie une nouvelle consigne sur {@code batiment/{piece}/actuateur/thermostat}. */
    void setConsigneTemperature(double t) throws RemoteException;

    /** Résumé de l'état de la pièce au format JSON (nom imposé par l'énoncé : getEtatJson). */
    String getEtatJson() throws RemoteException;

    /**
     * Ajout par rapport à l'énoncé : indique si aucun message capteur n'a été reçu depuis 30 s.
     * Nécessaire pour que le coordinateur puisse détecter la panne (tâche 3.1).
     */
    boolean isEnTimeout() throws RemoteException;

    /**
     * Partage d'état entre agents (énoncé, semaine 2 : « permettre aux agents de se consulter
     * mutuellement via RMI »).
     * <p>
     * L'agent qui reçoit cet appel va lui-même chercher l'agent de la pièce demandée dans le
     * registre RMI et lui demande son état : c'est un appel RMI <b>d'agent à agent</b>, sans passer
     * par le coordinateur.
     *
     * @param piece nom de la pièce voisine (salleA, salleB, cuisine)
     * @return le résumé JSON de la pièce voisine (même format que {@link #getEtatJson()})
     * @throws RemoteException si le voisin est introuvable dans le registre ou injoignable (panne)
     */
    String consulterVoisin(String piece) throws RemoteException;
}