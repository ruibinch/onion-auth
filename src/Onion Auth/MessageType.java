public enum MessageType {
		AUTH_SESSION_START(600), AUTH_SESSION_HS1(601), 
		AUTH_SESSION_INCOMING_HS1(602), AUTH_SESSION_HS2(603), 
		AUTH_SESSION_INCOMING_HS2(604), AUTH_LAYER_ENCRYPT(605),
		AUTH_LAYER_ENCRYPT_RESP(607), AUTH_LAYER_DECRYPT(606), 
		AUTH_LAYER_DECRYPT_RESP(608), AUTH_CIPHER_ENCRYPT(611),
		AUTH_CIPHER_ENCRYPT_RESP(612), AUTH_CIPHER_DECRYPT(613),
		AUTH_CIPHER_DECRYPT_RESP(614), AUTH_SESSION_CLOSE(609),
		AUTH_ERROR(610);

		private int val;

		MessageType(int val) {
			this.val = val;
		}

		public int getVal() {
			return this.val;
		}
	}