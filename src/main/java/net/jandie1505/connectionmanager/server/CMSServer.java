package net.jandie1505.connectionmanager.server;

import net.jandie1505.connectionmanager.CMClientEventListener;
import net.jandie1505.connectionmanager.enums.ConnectionBehavior;
import net.jandie1505.connectionmanager.enums.PendingClientState;
import net.jandie1505.connectionmanager.server.events.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * Server (CMS = ConnectionManager Server)
 */
public class CMSServer {
    // BASIC THINGS
    private final ServerSocket server;
    private final Map<UUID, CMSClient> clients;
    private final Thread garbageCollection;
    private final Thread pendingClientsThread;
    private final Thread thread;
    private final Thread eventQueueThread;
    private final Map<UUID, CMSPendingClient> pendingConnections;
    private final List<CMSServerEvent> eventQueue;
    private ConnectionBehavior defaultConnectionBehavior;
    private long connectionReactionTime;

    // SERVER SPECIFIC THINGS
    private final List<CMSServerEventListener> listeners;

    // CLIENT SPECIFIC THINGS
    private final List<CMClientEventListener> globalClientListeners;

    // SETUP
    public CMSServer(int port) throws IOException {
        this(port, null);
    }

    public CMSServer(int port, List<CMSServerEventListener> listeners) throws IOException {
        this.server = new ServerSocket(port);
        this.clients = Collections.synchronizedMap(new HashMap<>());
        this.defaultConnectionBehavior = ConnectionBehavior.REFUSE;
        this.pendingConnections = Collections.synchronizedMap(new HashMap<>());
        this.connectionReactionTime = 1000;
        this.eventQueue = Collections.synchronizedList(new ArrayList<>());

        if(listeners != null) {
            this.listeners = new ArrayList<>(listeners);
        } else {
            this.listeners = new ArrayList<>();
        }

        this.globalClientListeners = new ArrayList<>();

        CyclicBarrier startBarrier = new CyclicBarrier(5);

        // This will remove all clients which are disconnected
        this.garbageCollection = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startBarrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                }

                while(!Thread.currentThread().isInterrupted() && !server.isClosed() && isOperational()) {
                    synchronized(clients) {
                        Map<UUID, CMSClient> clientsCopy = Map.copyOf(clients);
                        for(UUID uuid : clientsCopy.keySet()) {
                            CMSClient client = clients.get(uuid);
                            if(client == null || client.isClosed()) {
                                clients.remove(uuid);
                            }
                        }
                    }

                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {}
                }
                close();
            }
        });
        this.garbageCollection.setName(this + "-GarbageCollectionThread");
        this.garbageCollection.setDaemon(true);

        this.eventQueueThread = new Thread(() -> {
            try {
                startBarrier.await();
            } catch (InterruptedException | BrokenBarrierException ignored) {
                Thread.currentThread().interrupt();
            }

            while(!Thread.currentThread().isInterrupted() && !server.isClosed() && this.isOperational()) {
                if(eventQueue.size() > 0) {
                    synchronized(this.eventQueue) {
                        for(CMSServerEventListener listener : List.copyOf(this.listeners)) {
                            try {
                                listener.onEvent(eventQueue.get(0));
                            } catch(Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    eventQueue.remove(0);
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {}
                }
            }
            this.close();
        });
        eventQueueThread.setName(this + "-EventHandlerThread");

        this.pendingClientsThread = new Thread(() -> {
            try {
                startBarrier.await();
            } catch (InterruptedException | BrokenBarrierException ignored) {
                Thread.currentThread().interrupt();
            }

            while(!Thread.currentThread().isInterrupted() && !this.server.isClosed() && this.isOperational()) {
                synchronized(pendingConnections) {
                    Map<UUID, CMSPendingClient> pendingConnectionsCopy = Map.copyOf(pendingConnections);
                    for(UUID uuid : pendingConnectionsCopy.keySet()) {
                        CMSPendingClient client = pendingConnections.get(uuid);
                        try {
                            if(client.getTime() > 0) {
                                client.setTime(client.getTime() - 1);
                                Thread.sleep(1);
                                checkPendingClient(uuid);
                            } else {
                                if(defaultConnectionBehavior == ConnectionBehavior.ACCEPT) {
                                    client.setState(PendingClientState.ACCEPTED);
                                } else {
                                    client.setState(PendingClientState.DENIED);
                                }
                                checkPendingClient(uuid);
                            }
                        } catch(InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {}
            }
            this.close();
            if(this.server.isClosed()) {
                synchronized(pendingConnections) {
                    for(UUID uuid : pendingConnections.keySet()) {
                        CMSPendingClient client = pendingConnections.get(uuid);
                        client.setState(PendingClientState.DENIED);
                        checkPendingClient(uuid);
                    }
                }
            }
        });
        this.pendingClientsThread.setName(this + "-PendingClientsThread");

        this.thread = new Thread(() -> {
            try {
                startBarrier.await();
            } catch (InterruptedException | BrokenBarrierException ignored) {
                Thread.currentThread().interrupt();
            }

            while(!Thread.currentThread().isInterrupted() && !this.server.isClosed() && this.isOperational()) {
                try {
                    CMSPendingClient client = new CMSPendingClient(server.accept(), connectionReactionTime);
                    UUID uuid = getRandomUniqueId();
                    pendingConnections.put(uuid, client);
                    this.fireEvent(new CMSServerConnectionAttemptEvent(this, uuid, client));
                } catch (IOException e) {
                    Thread.currentThread().interrupt();
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {}
            }
            this.close();
        });
        this.thread.setName(this + "-ConnectionThread");


        this.garbageCollection.start();
        this.eventQueueThread.start();
        this.pendingClientsThread.start();
        this.thread.start();

        try {
            startBarrier.await();
        } catch (InterruptedException | BrokenBarrierException ignored) {
            Thread.currentThread().interrupt();
        }

        this.fireEvent(new CMSServerStartedEvent(this));
    }

    private void checkPendingClient(UUID uniqueId) {
        CMSPendingClient pendingClient = this.pendingConnections.get(uniqueId);
        switch(pendingClient.getState()) {
            case ACCEPTED:
                CMSClient client = new CMSClient(pendingClient.getSocket(),this, globalClientListeners);
                clients.put(uniqueId, client);
                this.pendingConnections.remove(uniqueId);
                this.fireEvent(new CMSServerConnectionAcceptedEvent(this, client));
                break;
            case DENIED:
                this.pendingConnections.remove(uniqueId);
                try {
                    pendingClient.getSocket().close();
                } catch(IOException ignored) {
                    // IGNORED
                }
                this.fireEvent(new CMSServerConnectionRefusedEvent(this, uniqueId, pendingClient));
                break;
        }
    }

    // CONNECTION ATTEMPTS

    /**
     * Returns the current default connection behavior
     * @return ConnectionBehavior
     */
    public ConnectionBehavior getDefaultConnectionBehavior() {
        return this.defaultConnectionBehavior;
    }

    /**
     * Set the default connection behavior
     * @param behavior ConnectionBehavior
     */
    public void setDefaultConnectionBehavior(ConnectionBehavior behavior) {
        this.defaultConnectionBehavior = behavior;
    }

    /**
     * Get all pending connections
     * @return Map of UUIDs and pending connections
     */
    public Map<UUID, CMSPendingClient> getPendingConnections() {
        return Map.copyOf(this.pendingConnections);
    }

    /**
     * Get the time the user has time to accept/deny a pending connection.
     * After that time, the default connection behavior will be used for that.
     * @return time
     */
    public long getConnectionReactionTime() {
        return this.connectionReactionTime;
    }

    /**
     * Set the time the user has time to accept/deny a pending connection.
     * After that time, the default connection behavior will be used for that.
     * @param connectionReactionTime time (1-512000)
     */
    public void setConnectionReactionTime(long connectionReactionTime) {
        if(connectionReactionTime >= 1 && connectionReactionTime <= 512000) {
            this.connectionReactionTime = connectionReactionTime;
        }
    }

    // CLIENT UUIDS
    private UUID getRandomUniqueId() {
        UUID uuid = UUID.randomUUID();
        boolean unique = true;
        synchronized(this.clients) {
            for(UUID compare : this.clients.keySet()) {
                if(uuid.equals(compare)) {
                    unique = false;
                }
            }
        }
        synchronized(this.pendingConnections) {
            for(UUID compare : this.pendingConnections.keySet()) {
                if(uuid.equals(compare)) {
                    unique = false;
                }
            }
        }
        if(unique) {
            return uuid;
        } else {
            return this.getRandomUniqueId();
        }
    }

    /**
     * Returns a map of the clients and their UUIDs
     * @return Map
     */
    public Map<UUID, CMSClient> getClients() {
        return this.clients;
    }

    /**
     * Get UUID of Client
     * @param client CMSClient
     * @return UUID
     */
    protected UUID getIdOfClient(CMSClient client) {
        synchronized(this.clients) {
            for(UUID uuid : this.clients.keySet()) {
                CMSClient c = this.clients.get(uuid);
                if(client == c) {
                    return uuid;
                }
            }
        }
        return null;
    }

    /**
     * Returns a list of all clients
     * @return List of CMSClients
     */
    public List<CMSClient> getClientList() {
        List<CMSClient> returnList = new ArrayList<>();
        synchronized(this.clients) {
            for(UUID uuid : this.clients.keySet()) {
                returnList.add(this.clients.get(uuid));
            }
        }
        return returnList;
    }

    /**
     * Returns a client with a specific unique id
     * @param id UUID
     * @return CMSClient
     */
    public CMSClient getClientById(UUID id) {
        return this.clients.get(id);
    }

    // CLOSE
    /**
     * Close all clients. This will not close the server.
     */
    public void closeAll() {
        for(UUID uuid : this.clients.keySet()) {
            CMSClient client = this.clients.get(uuid);
            client.close();
        }
    }

    /**
     * Close the server and all its clients.
     */
    public void close() {
        try {
            this.server.close();
        } catch(Exception e) {
            e.printStackTrace();
        }
        if(this.thread != null) {
            this.thread.stop();
        }
        try {
            this.closeAll();
        } catch(Exception ignored) {}

        this.clients.clear();
    }

    /**
     * Returns whether the server is closed or not.
     * @return boolean
     */
    public boolean isClosed() {
        return this.server.isClosed();
    }

    /**
     * Returns whether the server is operational (means all threads are alive) or not.
     * @return boolean
     */
    public boolean isOperational() {
        boolean operationalCondition = this.garbageCollection.isAlive() && this.eventQueueThread.isAlive() && this.thread.isAlive() && this.pendingClientsThread.isAlive();

        try {
            if(!operationalCondition) {
                this.close();
            }
        } catch (Exception ignored) {
            // NOT REQUIRED
        }

        return operationalCondition;
    }

    // LISTENERS
    /**
     * Add a server listener
     * @param listener listener
     */
    public void addListener(CMSServerEventListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Remove a server listener
     * @param listener listener
     */
    public void removeListener(CMSServerEventListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * Get a list of all server listeners
     * @return list of listeners
     */
    public List<CMSServerEventListener> getListeners() {
        return this.listeners;
    }

    // GLOBAL LISTENERS
    /**
     * Add a global client listener
     * @param listener listener
     */
    public void addGlobalListener(CMClientEventListener listener) {
        this.globalClientListeners.add(listener);
    }

    /**
     * Remove a global client listener
     * @param listener listener
     */
    public void removeGlobalListener(CMClientEventListener listener) {
        this.globalClientListeners.remove(listener);
    }

    /**
     * Get a list of global client listeners
     * @return list of listeners
     */
    public List<CMClientEventListener> getGlobalListeners() {
        return this.globalClientListeners;
    }

    // EVENTS
    protected void fireEvent(CMSServerEvent event) {
        synchronized(this.eventQueue) {
            eventQueue.add(event);
        }
    }
}
