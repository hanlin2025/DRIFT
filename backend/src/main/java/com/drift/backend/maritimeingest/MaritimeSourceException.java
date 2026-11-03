package com.drift.backend.maritimeingest;

/** A source could not be read this run. The message never contains a key. */
class MaritimeSourceException extends RuntimeException {

	MaritimeSourceException(String message) {
		super(message);
	}
}
