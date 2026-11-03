package com.drift.backend.maritimeingest;

import java.net.URI;
import java.util.Map;

/** One GET that returns the body of a 2xx response. Anything else throws, so a collector run can skip that source. */
interface MaritimeHttp {

	String get(URI uri, Map<String, String> headers);
}
