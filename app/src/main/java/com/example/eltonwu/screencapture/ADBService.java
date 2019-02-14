package com.example.eltonwu.screencapture;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.example.eltonwu.screencapture.adb.AdbConnection;
import com.example.eltonwu.screencapture.adb.AdbCrypto;
import com.example.eltonwu.screencapture.adb.AdbStream;
import com.example.eltonwu.screencapture.adb.DefaultAdbBase64;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;

public class ADBService extends Service {
    private static final String TAG = "ADBService";
    private AdbConnection ADBInstance;
    private AdbConnectThread adbConnectThread;
    private ServerThread mSeverThread;

    public ADBService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new IADBInterface.Stub() {
            @Override
            public boolean isConnected() throws RemoteException {
                boolean result = false;
                if(ADBInstance != null){
                    result = ADBInstance.isConnected();
                }
                return result;
            }

            @Override
            public void start() throws RemoteException {
                stopADB();
                adbConnectThread = new AdbConnectThread();
                adbConnectThread.start();
            }

            @Override
            public void stop() throws RemoteException {
                stopADB();
            }
        };
    }

    private void stopADB(){
        try {
            if(adbConnectThread != null){
                adbConnectThread.interrupt();
                try {
                    adbConnectThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if(mSeverThread != null){
                mSeverThread.mRunning = false;
                if(mSeverThread.mServerSocket != null){
                    mSeverThread.mServerSocket.close();
                }
                try {
                    mSeverThread.join();
                } catch (InterruptedException ignored) {}
            }
            adbConnectThread = null;
            if(ADBInstance != null){
                ADBInstance.close();
                ADBInstance = null;
            }
        }catch (IOException e){
            Log.w(TAG,"close adb encounter error :"+e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        adbConnectThread = new AdbConnectThread();
        adbConnectThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        new Thread(new Runnable() {
            @Override
            public void run() {
                stopADB();
            }
        }).start();
        Intent intent = new Intent();
        intent.setAction("com.example.eltonwu.remoteadb.adb.destory");
        sendBroadcast(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this)
                .setContentTitle("Warning!")
                .setContentText("This May be Virus")
                .build();
        startForeground(233,notification);
        return START_STICKY;
    }

    private static AdbCrypto setupCrypto(String pubKeyFile, String privKeyFile)
            throws NoSuchAlgorithmException, InvalidKeySpecException, IOException
    {
        File pub = new File(pubKeyFile);
        File priv = new File(privKeyFile);
        AdbCrypto c = null;

        // Try to load a key pair from the files
        if (pub.exists() && priv.exists())
        {
            try {
                c = AdbCrypto.loadAdbKeyPair(DefaultAdbBase64.getInstance(), priv, pub);
            } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
                // Failed to read from file
                c = null;
            }
        }

        if (c == null)
        {
            // We couldn't load a key, so let's generate a new one
            c = AdbCrypto.generateAdbKeyPair(DefaultAdbBase64.getInstance());

            // Save it
            c.saveAdbKeyPair(priv, pub);
            Log.i(TAG,"Generated new keypair");
        }
        else
        {
            Log.i(TAG,"Loaded existing keypair");
        }
        return c;
    }

    private class AdbConnectThread extends Thread {

        @Override
        public void run() {
            Socket sock;
            AdbCrypto crypto;

            File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/adb");
            if(!dir.exists()){
                boolean mkdirs = dir.mkdirs();
                Log.i(TAG,"creare dir result:"+mkdirs);
            }
            String pubkey = dir.getAbsolutePath()+"/pub.key";
            String prikey = dir.getAbsolutePath()+"/priv.key";

            // Setup the crypto object required for the AdbConnection
            try {
                crypto = setupCrypto(pubkey, prikey);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
                e.printStackTrace();
                return;
            }

            Log.i(TAG,"Socket connecting...");
            try {
                sock = new Socket("127.0.0.1", 5555);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            Log.i(TAG,"Socket connected");

            // Construct the AdbConnection object
            try {
                ADBInstance = AdbConnection.create(sock, crypto);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            // Start the application layer connection process
            Log.i(TAG,"ADB connecting...");
            try {
                ADBInstance.connect();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return;
            }
            Log.i(TAG,"ADB connected");
            mSeverThread = new ServerThread();
            mSeverThread.start();
        }
    }

    private class ServerThread extends Thread {
        ServerSocket mServerSocket;
        ArrayList<ClientThread> mClients = new ArrayList<>();
        boolean mRunning = true;
        @Override
        public void run() {
            setName("Sever Thread@"+getId());
            try {
                mServerSocket = new ServerSocket(56665);
                while(ADBInstance.isConnected() && mRunning){
                    Socket client = mServerSocket.accept();
                    Log.i(TAG,"incoming client:"+client.getInetAddress().getHostAddress());
                    ClientThread clientThread = new ClientThread(client);
                    clientThread.start();

                    mClients.add(clientThread);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            Log.i(TAG,"sever closed");

            //wait clients stop
            for(ClientThread client : mClients){
                try {
                    client.mRunning = false;
                    client.interrupt();
                    client.join();
                } catch (InterruptedException ignored) {}
            }
            mClients.clear();
        }
    }

    private static class ScreenFrameBuffer{
        int size;
        int depth;
        int width;
        int height;

        ScreenFrameBuffer(int size, int depth, int width, int height) {
            this.size = size;
            this.depth = depth;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return "ScreenFrameBuffer{" +
                    "size=" + size +
                    ", depth=" + depth +
                    ", width=" + width +
                    ", height=" + height +
                    '}';
        }
    }

    private class ClientThread extends Thread {
        String client_mode = "null";
        String clientMessage;

        volatile boolean isClientMessageAvailable = false;
        boolean isClientQuit = false;

        ClientReadThread clientReadThread;
        private Socket mClient;
        boolean mRunning = true;
        private AdbStream ShellStream;
        private boolean isScreenCatInited = false;
        private ScreenFrameBuffer mFrameBuffer;

        ClientThread(Socket client){
            mClient = client;
        }

        @Override
        public void run() {
            setName("Client@"+mClient.getInetAddress().getHostAddress()+"@"+getId());
            OutputStream outputStream = null;
            InputStream inputStream = null;
            try {
                outputStream = mClient.getOutputStream();
                inputStream  = mClient.getInputStream();
                final BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                PrintWriter pw = new PrintWriter(new OutputStreamWriter(outputStream));
                clientReadThread = new ClientReadThread(br);
                clientReadThread.start();

                while(!isClientQuit){
                    if(!mRunning){
                        break;
                    }
                    feedbackToClient(pw,outputStream);
                }
            } catch (IOException e) {
                Log.w(TAG,"client exception :"+e.getMessage());
            }  finally {
                try {
                    if(inputStream != null){
                        inputStream.close();
                    }
                    if(outputStream != null){
                        outputStream.close();
                    }
                    if(mClient != null){
                        mClient.close();
                    }
                    if(ShellStream != null){
                        ShellStream.close();
                        ShellStream = null;
                    }
                }catch (IOException e){
                    Log.i(TAG,"clean up exception :"+e.getMessage());
                }
            }
            Log.i(TAG,"Client :"+mClient.getInetAddress().getHostAddress()+" quit");
            try {
                clientReadThread.join();
            } catch (InterruptedException ignored) {}
        }

        private boolean shellMode() throws IOException {
            // Open the shell stream of ADB
            boolean result = true;
            try {
                ShellStream = ADBInstance.open("shell:");
            } catch (UnsupportedEncodingException | InterruptedException e) {
                e.printStackTrace();
                result = false;
            }
            return result;
        }
        private boolean devMode(String fullPath) throws IOException {
            boolean result = true;
            try {
                ShellStream = ADBInstance.open("dev:"+fullPath);
            } catch (UnsupportedEncodingException | InterruptedException e) {
                e.printStackTrace();
                result = false;
            }
            return result;
        }
        private boolean screencatMode() throws IOException{
            boolean result = true;
            try {
                ShellStream = ADBInstance.open("framebuffer:");
            } catch (UnsupportedEncodingException | InterruptedException e) {
                e.printStackTrace();
                result = false;
            }
            return result;
        }

        private void feedbackToClient(PrintWriter pw,OutputStream os){
            if(client_mode.equals("null")){
                if(isClientMessageAvailable){
                    pw.println(clientMessage);
                    pw.flush();
                    isClientMessageAvailable = false;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {}
                }
            }else if(client_mode.equals("shell")){
                try {
                    // Print each thing we read from the shell stream
                    if(ShellStream != null){
                        byte[] data = ShellStream.read();
                        String string = null;
                        if(data.length > 0){
                            if(data[0] >= 0 && data[0] <= 32){
                                string = new String(data,1,data.length-1,"US-ASCII");
                            }
                        }
                        if(string == null){
                            string = new String(data,"US-ASCII");
                        }
                        pw.println(string);
                        pw.flush();
                    }
                } catch (InterruptedException | IOException e) {
                    e.printStackTrace();
                }
            }else if(client_mode.equals("pull")){
                if(ShellStream != null){
                    int sum = 0;
                    while(!ShellStream.isClosedNoBuffer()){
                        try {
                            byte[] data = ShellStream.read();
                            sum += data.length;
                            Log.i(TAG,"read bytes:"+data.length);
                            os.write(data);
                        } catch (InterruptedException | IOException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                    Log.i(TAG,"transfer completed:"+sum);
                    isClientQuit = true;
                }
            }else if(client_mode.equals("screencat")){
                if(ShellStream != null){
                    if(!isScreenCatInited){
                        try {
                            byte[] data = ShellStream.read();
                            ByteArrayInputStream bis = new ByteArrayInputStream(data);
                            if(data.length >= (5 * 4)){
                                byte[] aint = new byte[4];
                                bis.read(aint,0,4);
                                int tmp = ByteBuffer.wrap(aint).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                Log.i(TAG,"first data :"+tmp);
                                bis.read(aint,0,4);
                                int depth   = ByteBuffer.wrap(aint).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                bis.read(aint,0,4);
                                int size    = ByteBuffer.wrap(aint).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                bis.read(aint,0,4);
                                int width   = ByteBuffer.wrap(aint).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                bis.read(aint,0,4);
                                int height  = ByteBuffer.wrap(aint).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                mFrameBuffer = new ScreenFrameBuffer(size,depth,width,height);
                                Log.i(TAG,"framebuffer info :"+mFrameBuffer.toString());
                                isScreenCatInited = true;
                            }else{
                                throw new IOException("read bytes too few");
                            }
                            long start = System.currentTimeMillis();
                            Bitmap.Config config = Bitmap.Config.ARGB_8888;
                            if(mFrameBuffer.depth == 4){
                                config = Bitmap.Config.ARGB_8888;
                            }else if(mFrameBuffer.depth == 2){
                                config = Bitmap.Config.RGB_565;
                            }
                            Bitmap bitmap = Bitmap.createBitmap(mFrameBuffer.width, mFrameBuffer.height, config);
                            ByteBuffer byteBuffer = ByteBuffer.allocate(mFrameBuffer.size);
                            byte[] remainData = new byte[bis.available()];
                            int read = bis.read(remainData);
                            long now = System.currentTimeMillis() - start;
                            Log.i(TAG,"remain data:"+read+" :"+now);
                            bis.close();
                            byteBuffer.put(remainData);
                            int sum = read;
                            while (sum < mFrameBuffer.size){
                                byte[] bb = ShellStream.read();
                                int read_length;
                                if(sum + bb.length > mFrameBuffer.size){
                                    read_length = mFrameBuffer.size - sum;
                                }else{
                                    read_length = bb.length;
                                }
                                byteBuffer.put(bb,0,read_length);
                                sum += read_length;
//                                Log.i(TAG,"sum :"+sum+" ,this:"+bb.length+",read length:"+read_length);
                            }
                            now = System.currentTimeMillis() - start;
                            Log.i(TAG,"reading complete :"+now);
                            byteBuffer.rewind();
                            bitmap.copyPixelsFromBuffer(byteBuffer);
                            FileOutputStream fos = new FileOutputStream(new File(Environment.getExternalStorageDirectory(),"screencat.jpg"));
                            bitmap.compress(Bitmap.CompressFormat.JPEG,80,fos);
                            now = System.currentTimeMillis() - start;
                            Log.i(TAG,"file saved: "+now);
                            fos.close();
                        } catch (InterruptedException | IOException e) {
                            isClientQuit = true;
                        }
                    }
                }
            }
        }

        private class ClientReadThread extends Thread {
            BufferedReader br;

            @Override
            public void run() {
                while(mRunning){
                    try {
                        clientMessage = br.readLine();
                        if(clientMessage == null){
                            break;
                        }
                        if(client_mode.equals("null")){
                            if(clientMessage.equals("shell")){
                                if(shellMode()){
                                    Log.i(TAG,"change to Shell mode");
                                    client_mode = clientMessage;
                                }else{
                                    clientMessage = "open shell failed";
                                }
                            }else if(clientMessage.startsWith("pull")){
                                String[] split = clientMessage.split(" ");
                                if(split.length == 2){
                                    String fullPath= split[1];
                                    if(devMode(fullPath)){
                                        Log.i(TAG,"change to dev mode");
                                        client_mode = "pull";
                                    }
                                }else{
                                    clientMessage = "open dev failed";
                                }
                            }else if(clientMessage.equals("screencat")) {
                                if(screencatMode()){
                                    client_mode = "screencat";
                                }else{
                                    clientMessage = "open dev failed";
                                }
                            }else{
                                clientMessage = "unknown command";
                            }
                        }else if(client_mode.equals("shell")){
                            if(ShellStream != null){
                                ShellStream.write(" "+ clientMessage +"\n");
                            }
                        }else if(client_mode.equals("screencat")){
                            //TODO
                        }
                        isClientMessageAvailable = true;
                    } catch (IOException e) {
                        Log.w(TAG,"client quit:"+e.getMessage());
                        break;
                    } catch (InterruptedException e) {
                        Log.w(TAG,"adb out:"+e.getMessage());
                        break;
                    }
                }
                isClientQuit = true;
                if(ShellStream != null && !ShellStream.isClosedNoBuffer()){
                    try {
                        ShellStream.close();
                    } catch (IOException e) {
                        Log.w(TAG,"close shell failed:"+e.getMessage());
                    }
                }
            }

            ClientReadThread(BufferedReader br) {
                this.br = br;
            }
        }
    }
}
