import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;


public class Tracker {

    // tacker has specific port
    static final String ip = "127.0.0.1";
    static final int port = 5000;

    // Hash map for Registered peers. ( Username, Password )
    ConcurrentHashMap<String, String> registeredPeers = new ConcurrentHashMap<>();
    // Hash map to combine ( Username, Info ). For Info details please open Info.java file.
    ConcurrentHashMap<String, Info> usernameToInfo = new ConcurrentHashMap<>();
    // This Hash map help us to know which peers has every file. ( File, Hash map of Info )
    ConcurrentHashMap<String, ConcurrentHashMap<String, Info>> filesToInfo = new ConcurrentHashMap<>();
    // Stores all token ids.
    ArrayList<Integer> allTokenIds = new ArrayList<>();
    // Stores all file names
    ArrayList<String> allFiles;


    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public Tracker(String fileDownloadListPath) {
        // Reads all files from specific file in given path.
        allFiles = Util.readFileDownloadList(fileDownloadListPath);
        // initialize a hashmap for each file
        fillFilesToToken();
    }

    public void startServer() {
        ServerSocket providerSocket = null;
        Socket connection = null;
        try {
            providerSocket = new ServerSocket(this.port, 10);
            System.out.println("Tracker listening on port " + getPort());
            while (true) {
                connection = providerSocket.accept();
                //We start a thread
                //this thread will do the communication
                TrackerHandler ph = new TrackerHandler(connection);
                ph.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                providerSocket.close();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }
    }

    public static void main(String[] args){
        try{
            // start Tracker
            Tracker p = new Tracker(args[0]);
            p.startServer();

        }catch (Exception e) {
            System.out.println("Usage: java Tracker ip port");
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    public class TrackerHandler extends Thread {
        Socket socket;

        public TrackerHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() { //Protocol
            ObjectInputStream in = null;
            ObjectOutputStream out = null;
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                PeerToTracker req = (PeerToTracker) in.readObject();
                System.out.printf("[Tracker %s , %d] GOT REQUEST " + req.toString() + "\n", getIp(), getPort());

                // Depending on Input object performs certain functions.
                if (req.method == Method.REGISTER) {
                    // if peers gave an existing username
                    if (registeredPeers.containsKey(req.username)) {
                        //reply with fail to register
                        failureRegister(out);
                    } else {
                        // save peer's info
                        registeredPeers.put(req.username, req.password);
                        Info peerInfo = new Info(req.username);
                        usernameToInfo.put(req.username, peerInfo);
                        // reply with successful register
                        successRegister(out);
                    }
                } else if (req.method == Method.LOGIN) {
                    // if peers gave the username and password of a registered peer
                    if (registeredPeers.containsKey(req.username) && registeredPeers.get(req.username).equals(req.password)) {
                        // generate a random token id
                        int token_id = getRandomTokenId();
                        allTokenIds.add(token_id);
                        // reply with successful login
                        successLogin(out, token_id);

                        // peers answer with inform method
                        // they inform the tracker about their parts of files
                        // and if they are seeder for each one of them
                        // tracker saves these info
                        PeerToTracker secondInput = (PeerToTracker) in.readObject();
                        System.out.printf("[Tracker %s , %d] GOT PEER INFO " + secondInput.toString() + " \n", getIp(), getPort());
                        System.out.println("----");
                        Info infoTemp = usernameToInfo.get(req.username);
                        infoTemp.ip = secondInput.ip;
                        infoTemp.port = secondInput.port;
                        infoTemp.sharedDirectory = secondInput.sharedDirectory;
                        infoTemp.pieces = secondInput.pieces;
                        infoTemp.seederBit = secondInput.seederBit;

                        for (String i : infoTemp.sharedDirectory) {
                            filesToInfo.get(i).put(secondInput.username, infoTemp);
                        }
                    } else {
                        // reply with unsuccessful login
                        failureLogin(out);
                    }
                } else if (req.method == Method.LOGOUT) {
                    // if peers gave a correct token id
                    // it means that they are logged in, so they can logout
                    if(allTokenIds.contains(req.token_id)) {
                        // dlete peer's info about what files they have
                        allTokenIds.remove((Integer) req.token_id);
                        ArrayList<String> filesOfRemoved = usernameToInfo.get(req.username).sharedDirectory;
                        for(String i: filesOfRemoved){
                            // It removes from Files_toInfo all the Shared_directory files in the hashmap with key "req.token_id" which is the token given from the peer.
                            filesToInfo.get(i).remove(req.username);
                        }
                        // reply with successful logout
                        successLogout(out);
                    }else{
                        // reply with unsuccessful logout
                        failureLogout(out);
                    }
                } else if (req.method == Method.LIST) {
                    // reply with a list with the names of the file
                    replyList(out);
                } else if (req.method == Method.DETAILS) {
                    // get peers with at least one piece of the required file
                    ConcurrentHashMap<String, Info> peersWithFile =  filesToInfo.get(req.fileName);
                    ArrayList<Info> activeFiles = new ArrayList<>();
                    // check that all peers are active
                    for(Map.Entry<String, Info> i : peersWithFile.entrySet()) {
                        StatusCode status = checkActive(i.getValue().ip, i.getValue().port);
                        if (status != null) {
                            // if peer is not active delete its entries
                            if (!status.equals(StatusCode.PEER_ISACTIVE)) {
                                allTokenIds.remove(i.getValue().tokenId);
                                ArrayList<String> filesOfRemoved = usernameToInfo.get(i.getKey()).sharedDirectory;
                                for (String j : filesOfRemoved) {
                                    filesToInfo.get(j).remove(i.getKey());
                                }
                                peersWithFile.remove(i.getKey());
                            }
                            // if peer is active add it to the answered peers
                            else {
                                activeFiles.add(i.getValue());
                            }
                        }
                    }
                    // at least one peer has the file
                    if(!peersWithFile.isEmpty()) {
                        // reply with peers' info with the file
                        System.out.println("Peers with the file: "+peersWithFile.size());
                        replyDetails(out, activeFiles);
                    }else{
                        // nobody has the file
                        System.out.println("No peer with this file");
                        replyDetailsNot(out);
                    }
                } else if (req.method == Method.NOTIFY_SUCCESSFUL) {
                    // Update Files_toInfo data structure, essentially we add peer's info to the array of the peers that have the specific file
                    Info infoTemp = usernameToInfo.get(req.username);
                    // Add new downloaded file
                    infoTemp.sharedDirectory.add(req.fileName);
                    filesToInfo.get(req.fileName).put(req.username, infoTemp);
                    // Moreover, increase count downloads index.
                    usernameToInfo.get(req.peerUsername).countDownloads++;
                } else if(req.method == Method.NOTIFY_FAILED){
                    usernameToInfo.get(req.peerUsername).countFailures++;
                }else if(req.method == Method.ALL_PEERS){
                    replyAllPeers(out);
                }else if(req.method == Method.NOTIFY_SUCCESSFUL_PART){
                    // Update Files_toInfo data structure, essentially we add peer's info to the array of the peers that have the specific file

                    Info infoTemp = usernameToInfo.get(req.username);

                    // first time that this peer get a part for this file
                    if(!infoTemp.sharedDirectory.contains(req.fileName)){
                        filesToInfo.get(req.fileName).put(req.username, infoTemp);
                        infoTemp.sharedDirectory.add(req.fileName);
                        infoTemp.pieces.put(req.fileName, new ArrayList<>());
                        infoTemp.seederBit.put(req.fileName, false);
                    }
                    if(!infoTemp.pieces.get(req.fileName).contains(req.id)) {
                        infoTemp.pieces.get(req.fileName).add(req.id);
                    }
                    // Moreover, increase count downloads index.
                    usernameToInfo.get(req.peerUsername).countDownloads++;
                }else if(req.method == Method.I_AM_SEEDER){
                    // change peer's seeder bit
                    Info infoTemp = usernameToInfo.get(req.username);
                    infoTemp.seederBit.replace(req.fileName, true);
                }else{
                    System.out.println("Got unexpected request");
                }
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    // Check peer if is active. Return his status
    public static StatusCode checkActive(String peerIp, int peerPort){
        Socket socket = null;
        ObjectOutputStream out = null;
        ObjectInputStream in = null;
        try {
            socket = new Socket(peerIp, peerPort);
            System.out.println("Connected to peer on port "+peerIp+" port "+peerPort);

            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            AnyToPeer anytopeer = new AnyToPeer();
            anytopeer.method = Method.CHECK_ACTIVE_TRACKER_TO_PEER;
            System.out.println(anytopeer.toString());
            out.writeObject(anytopeer);

            PeerToTracker reply = (PeerToTracker) in.readObject();
            System.out.println(reply.toString());

            return reply.statusCode;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        try{
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public void fillFilesToToken(){
        for(String i : allFiles){
            filesToInfo.put(i, new ConcurrentHashMap<String, Info>());
        }
    }

    public int getRandomTokenId() {
        int tokenid = 0;
        while(allTokenIds.contains(tokenid)) {
            // Sets a token id within range 0 to MAX INT.
            tokenid = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
        }
        return tokenid;
    }

    // All the following functions refer to replies to a peer.
    public void failureRegister(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.UNSUCCESSFUL_REGISTER;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void successRegister(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.SUCCESSFUL_REGISTER;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void failureLogin(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.UNSUCCESSFUL_LOGIN;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void successLogin(ObjectOutputStream out, int token) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        allTokenIds.add(token);
        reply.tokenΙd = token;
        reply.statusCode = StatusCode.SUCCESSFUL_LOGIN;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void successLogout(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.SUCCESSFUL_LOGOUT;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void failureLogout(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.UNSUCCESSFUL_LOGOUT;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void replyList(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.allFiles = allFiles;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void replyDetails(ObjectOutputStream out, ArrayList<Info> withFile) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.FILE_FOUND;
        reply.peerInfo = withFile;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void replyDetailsNot(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.statusCode = StatusCode.FILE_NOTFOUND;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

    public void replyAllPeers(ObjectOutputStream out) throws IOException {
        AnyToPeer reply = new AnyToPeer();
        reply.usernameToInfo = this.usernameToInfo;
        System.out.println("REPLY: "+reply.toString());
        System.out.println("----");
        out.writeObject(reply);
    }

}