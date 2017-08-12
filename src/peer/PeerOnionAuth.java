import java.io.File;
import java.io.*;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.spec.*;
import javax.crypto.spec.*;
import javax.crypto.*;
import java.security.*;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.math.BigInteger;
import java.util.*;
import javax.crypto.KeyAgreement;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

public class PeerOnionAuth {
	private DataOutputStream toOnion;
	private DataInputStream fromOnion;
	private ServerSocket welcomeSkt;  // wait for sender to connect
	private Socket skt;
	private PrivateKey dhPri;
	private PublicKey dhPub;
	final private KeyFactory rsaKeyFactory;
	private MessageDigest sha256;
	private HashMap<Integer, MessageType> sessionTypeMap; // map session ID to message type
	private HashMap<Integer, SecretKeySpec> sessionKeyMap; // map session ID to session key
	private SecureRandom prng;
	private PublicKey peerHostkey;
	private PrivateKey rsaPri;
	private PublicKey rsaPub;

	private int requestID = 12;

	public PeerOnionAuth() throws Exception {
		//crypto set up
	    this.rsaKeyFactory = KeyFactory.getInstance("RSA");
		this.sessionTypeMap = new HashMap<Integer, MessageType>();
		this.sessionKeyMap = new HashMap<Integer, SecretKeySpec>();
		this.prng = SecureRandom.getInstance("SHA1PRNG");
		this.sha256 = MessageDigest.getInstance("SHA-256");
		this.generateRSAKeyPair();
	}

	public void listenForConnection(int portNum) throws Exception {
		this.welcomeSkt = new ServerSocket(portNum);
		System.out.println("Onion Authentication listens at port " + portNum);
		this.skt = this.welcomeSkt.accept();
		System.out.println("Incoming connection from Onion accepted");
		this.toOnion = new DataOutputStream(this.skt.getOutputStream());
		this.fromOnion = new DataInputStream(this.skt.getInputStream());
		
		do {
			receiveMessage();
		} while (true);

	}

	public void readHostKey(String hostkeyFile) throws Exception {
		byte[] keyBytes = Files.readAllBytes(Paths.get(hostkeyFile));
	    final X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
	    KeyFactory kf = KeyFactory.getInstance("RSA");
	    this.peerHostkey = kf.generatePublic(spec);
	}

	private void receiveMessage() throws Exception {
		//read 16-bit payload size
		byte[] sizeBytes = new byte[3];
		byte[] bytes = new byte[2];
		this.fromOnion.read(bytes, 0, 2);
		System.arraycopy(bytes, 0, sizeBytes, 1, bytes.length);
		int size = new BigInteger(sizeBytes).intValue();
		System.out.println("Payload size: " + size);

		//read 16-bit message type
		byte[] typeBytes = new byte[2];
		this.fromOnion.read(typeBytes, 0, 2);
		int typeVal = new BigInteger(typeBytes).intValue();
		MessageType type = MessageType.values()[typeVal];
		System.out.println("message type: " + type);

		switch(type) {
			case AUTH_SESSION_START: 
				handleAuthStart(size);
				break;
			case AUTH_SESSION_INCOMING_HS1: 
				handleIncomingHS1(size);
				break;
			case AUTH_SESSION_INCOMING_HS2: 
				handleIncomingHS2(size);
				break;
			case AUTH_LAYER_ENCRYPT: break;
			case AUTH_LAYER_ENCRYPT_RESP: break;
			case AUTH_LAYER_DECRYPT: break;
			case AUTH_LAYER_DECRYPT_RESP: break;
			case AUTH_CIPHER_ENCRYPT: break;
			case AUTH_CIPHER_ENCRYPT_RESP: break;
			case AUTH_CIPHER_DECRYPT: break;
			case AUTH_CIPHER_DECRYPT_RESP: break;
			case AUTH_SESSION_CLOSE: break;
			case AUTH_ERROR: break;
		}

	}

	public void handleAuthStart(int size) throws Exception {
		//read 32-bit reserved field
		byte[] reservedBytes = new byte[4];
		this.fromOnion.read(reservedBytes, 0, 4);
		int reserved = new BigInteger(reservedBytes).intValue();
		System.out.println("reserved: " + reserved);

		//read 32-bit request ID
		byte[] requestIDBytes = new byte[4];
		this.fromOnion.read(requestIDBytes, 0, 4);
		int requestID = new BigInteger(requestIDBytes).intValue();
		System.out.println("request ID: " + requestID);

		//read the hostkey(public key) as an object and save it for future use
		int peerHostkeySize = size - 12;
		System.out.println("peer hostkey size: " + peerHostkeySize);
		byte[] peerHostkeyBytes = new byte[peerHostkeySize];
		this.fromOnion.read(peerHostkeyBytes, 0, peerHostkeySize);
		this.peerHostkey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(peerHostkeyBytes));

		//verify the size of the peer hostkey
		if (this.peerHostkey.getEncoded().length != size - 12) {
			System.out.println("Hostkey size size does not match!");
		} else {
			System.out.println("Hostkey size check passed, okay to proceed!");
		}

		//reply to SESSION AUTH START
		this.sendHS1();
	}

	private void sendHS1() throws Exception {
		//16-bit size, in this case the size of the handshake payload
		this.generateDHKeyPair();
		byte[] dhPubBytes = this.dhPub.getEncoded();
		int size = 12 + dhPubBytes.length;
		byte[] sizeBytes = ByteBuffer.allocate(4).putInt(size).array();
		this.toOnion.write(Arrays.copyOfRange(sizeBytes, 2, 4));
		this.toOnion.flush();

		//16-bit message type
		byte[] typeBytes = ByteBuffer.allocate(4).putInt(
			MessageType.AUTH_SESSION_HS1.getVal()).array();
		this.toOnion.write(Arrays.copyOfRange(typeBytes, 2, 4));
		this.toOnion.flush();

		//16-bit reserved field of 0s
		this.toOnion.write(new byte[2]);
		this.toOnion.flush();

		//16-bit session ID
		int sessionID = this.prng.nextInt((1 << 16) - 1);
		byte[] sessionIDBytes = ByteBuffer.allocate(4).putInt(sessionID).array();
		this.toOnion.write(Arrays.copyOfRange(sessionIDBytes, 2, 4));
		this.toOnion.flush();
		this.sessionTypeMap.put(sessionID, MessageType.AUTH_SESSION_HS1);

		//32-bit request ID
		byte[] requestIDBytes = ByteBuffer.allocate(4).putInt(0).array();//change later
		this.toOnion.write(requestIDBytes);
		this.toOnion.flush();

		//handshake payload
		this.toOnion.write(dhPubBytes);
		this.toOnion.flush();

	}



	public void handleIncomingHS1(int size) throws Exception {
		//read 32-bit reserved field
		byte[] reservedBytes = new byte[4];
		this.fromOnion.read(reservedBytes, 0, 4);
		int reserved = new BigInteger(reservedBytes).intValue();

		//read 32-bit request ID
		byte[] requestIDBytes = new byte[4];
		this.fromOnion.read(requestIDBytes, 0, 4);
		int requestID = new BigInteger(requestIDBytes).intValue();
		

		//read HS1 handshake payload
		int peerDhPubSize = size - 12;
		byte[] peerDhPubBytes = new byte[peerDhPubSize];
		this.fromOnion.read(peerDhPubBytes, 0, size - 12);
		PublicKey peerDhPub = KeyFactory.getInstance("DiffieHellman").generatePublic(
			new X509EncodedKeySpec(peerDhPubBytes));


		//verify the size of the payload
		if (peerDhPub.getEncoded().length != size - 12) {
			System.out.println("Peer DH public key size does not match!");
		} else {
			System.out.println("Peer DH public key size check passed, okay to proceed!");
		}


		//generate common session key
		SecretKeySpec aesKeySpec = this.generateCommonSecretKey(peerDhPub);

		//reply to INCOMING HS1
		int sessionID = this.prng.nextInt((1 << 16) - 1);
		this.sessionTypeMap.put(sessionID, MessageType.AUTH_SESSION_HS2);
		this.sessionKeyMap.put(sessionID, aesKeySpec);
		this.sendHS2(sessionID);
	}

	private void sendHS2(int sessionID) throws Exception {
		
		//generate handshake payload signed (session key hash + own DH public key)

		//generate key hash
		SecretKeySpec aesKeySpec = this.sessionKeyMap.get(sessionID);
		this.sha256.update(aesKeySpec.getEncoded());
		byte[] digest = this.sha256.digest();
		this.sha256.reset();

		//generate signature
		Signature dsa = Signature.getInstance("SHA256withRSA");
		dsa.initSign(this.rsaPri);
		byte[] payload = new byte[digest.length + this.dhPub.getEncoded().length];
		System.arraycopy(digest, 0, payload, 0, digest.length);
		System.arraycopy(this.dhPub.getEncoded(), 0, payload, digest.length, this.dhPub.getEncoded().length);
		dsa.update(payload);
		byte[] signature = dsa.sign();

		//16-bit size
		int size = signature.length + this.dhPub.getEncoded().length + digest.length + 12;
		byte[] sizeBytes = ByteBuffer.allocate(4).putInt(size).array();
		this.toOnion.write(Arrays.copyOfRange(sizeBytes, 2, 4));
		this.toOnion.flush();

		//16-bit message type
		byte[] typeBytes = ByteBuffer.allocate(4).putInt(
			MessageType.AUTH_SESSION_HS2.getVal()).array();
		this.toOnion.write(Arrays.copyOfRange(typeBytes, 2, 4));
		this.toOnion.flush();

		//16-bit reserved field
		this.toOnion.write(new byte[2]);
		this.toOnion.flush();

		//16-bit session ID
		byte[] sessionIDBytes = ByteBuffer.allocate(4).putInt(sessionID).array();
		this.toOnion.write(Arrays.copyOfRange(sessionIDBytes, 2, 4));
		this.toOnion.flush();


		//32-request ID
		byte[] requestIDBytes = ByteBuffer.allocate(4).putInt(0).array();
		this.toOnion.write(requestIDBytes);
		this.toOnion.flush();

		//write the payload and signature
		this.toOnion.write(this.dhPub.getEncoded());
		this.toOnion.flush();
		this.toOnion.write(digest);
		this.toOnion.flush();
		this.toOnion.write(signature);
		this.toOnion.flush();
	}

	public void handleIncomingHS2(int size) throws Exception {
		//read 16-bit reserved field
		byte[] reservedBytes = new byte[2];
		this.fromOnion.read(reservedBytes, 0, 2);

		//read 16-bit session ID
		byte[] sessionIDBytes = new byte[3];
		byte[] bytes = new byte[2];
		this.fromOnion.read(bytes, 0, 2);
		System.arraycopy(bytes, 0, sessionIDBytes, 1, bytes.length);
		int sessionID = new BigInteger(sessionIDBytes).intValue();

		//read 32-request ID
		byte[] requestIDBytes = new byte[4];
		int requestID = this.fromOnion.read(requestIDBytes, 0, 4);

		//read HS2 payload (session key hash + peer DH public key)
		int peerDhPubSize = size - 12 - 32 - 512;
		byte[] peerDhPubBytes = new byte[peerDhPubSize];
		this.fromOnion.read(peerDhPubBytes, 0, peerDhPubSize);
		PublicKey peerDhPub = KeyFactory.getInstance("DiffieHellman").generatePublic(
			new X509EncodedKeySpec(peerDhPubBytes));
		byte[] digest = new byte[32];
		this.fromOnion.read(digest, 0, 32);
		byte[] signature = new byte[512];
		this.fromOnion.read(signature, 0, 512);

		//verify payload size
		if (!(peerDhPub.getEncoded().length + signature.length + digest.length + 12 == size)) {
			System.out.println("Handshake payload does not match!");
		} else {
			System.out.println("Handshake payload size matches, okay to proceed!");
		}


		//generate common session key
		SecretKeySpec aesKeySpec = this.generateCommonSecretKey(peerDhPub);

		//verify key hash
		this.sha256.update(aesKeySpec.getEncoded());
		byte[] computedDigest = this.sha256.digest();
		this.sha256.reset();
		if (!Arrays.equals(digest, computedDigest)) {
			System.out.println("Session key hash does not match!");
		} else {
			System.out.println("Session key hash matches, okay to proceed!");
		}

		//verify signature
		Signature sig = Signature.getInstance("SHA256withRSA");
		sig.initVerify(this.rsaPub);
		byte[] payload = new byte[digest.length + peerDhPub.getEncoded().length];
		System.arraycopy(digest, 0, payload, 0, digest.length);
		System.arraycopy(peerDhPub.getEncoded(), 0, payload, 
			digest.length, peerDhPub.getEncoded().length);
		sig.update(payload);
		if (!sig.verify(signature)) {
			System.out.println("Payload signature does not match!");
		} else {
			System.out.println("Payload signature matches, okay to proceed!");
		}

		//check if session ID exists, it should match one of the session IDs from HS1
		MessageType type = this.sessionTypeMap.get(sessionID);
		if (type == MessageType.AUTH_SESSION_HS1) {
			this.sessionKeyMap.put(sessionID, aesKeySpec);
		}

	}

	public void handlelayerEncrypt() throws Exception {

	}

	private void sendLayerEncryptRESP() throws Exception {

	}

	public void handlelayerDecrypt() throws Exception {

	}

	private void sendLayerDecryptRESP() throws Exception {

	}

	public void handleAuthClose() throws Exception {

	}

	private void sendAuthError() throws Exception {

	}

	private void generateDHKeyPair() throws Exception {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
		keyPairGenerator.initialize(2048);
		KeyPair keyPair = keyPairGenerator.generateKeyPair();
		this.dhPri = keyPair.getPrivate();
		this.dhPub = keyPair.getPublic();
	}

	private void generateRSAKeyPair() throws Exception {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(4096);
		KeyPair keyPair = keyPairGenerator.generateKeyPair();
		this.rsaPri = keyPair.getPrivate();
		this.rsaPub = keyPair.getPublic();
	}

	private SecretKeySpec generateCommonSecretKey(PublicKey peerDhPub) throws Exception {
		//generate the common secret
		KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
		keyAgreement.init(this.dhPri);
        keyAgreement.doPhase(peerDhPub, true);

		//construct the 256-bit common AES key 
		byte[] rawAESKey = new byte[32];
		byte[] rawSecret = keyAgreement.generateSecret();
		System.arraycopy(rawSecret, 0, rawAESKey, 0, rawAESKey.length);
		return new SecretKeySpec(rawAESKey, 0, rawAESKey.length, "AES");
	}

	//encrypt with a random IV in GCM mode
	public byte[] encrypt(SecretKeySpec aesKey, byte[] payload, byte[] iv) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
		return cipher.doFinal(payload);
	}

	//decrypt with a given IV in GCM mode
	public byte[] decrypt(SecretKeySpec aesKey, byte[] payload, byte[] iv) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
		return cipher.doFinal(payload);	
	}

}