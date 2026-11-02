package com.drift.backend.maritimechange;

final class MaritimeImo {

	private MaritimeImo() {
	}

	static boolean valid(String imo) {
		if (imo == null || imo.length() != 7) {
			return false;
		}
		int sum = 0;
		for (int index = 0; index < 6; index++) {
			char digit = imo.charAt(index);
			if (digit < '0' || digit > '9') {
				return false;
			}
			sum += (digit - '0') * (7 - index);
		}
		char check = imo.charAt(6);
		return check >= '0' && check <= '9' && sum % 10 == (check - '0');
	}
}
