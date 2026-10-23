package com.drift.backend.shipment;

import org.springframework.stereotype.Service;

import com.drift.backend.ais.feed.AisFeedStatus;
import com.drift.backend.ais.feed.AisFeedStatusService;

/**
 * Reads the AIS feed while a shipment response is assembled.
 * The read lives in {@link ShipmentDetailResponse#from} so detail and the importer link stay together.
 */
@Service
public class ShipmentAisFeed {

	private static final ThreadLocal<ShipmentAisFeed> CURRENT = new ThreadLocal<>();

	private final AisFeedStatusService feeds;

	public ShipmentAisFeed(AisFeedStatusService feeds) {
		this.feeds = feeds;
	}

	static void use(ShipmentAisFeed feed) {
		CURRENT.set(feed);
	}

	static void clear() {
		CURRENT.remove();
	}

	/**
	 * Null when this response is assembled outside a request. A request always reads the feed.
	 */
	static AisFeedStatus current() {
		ShipmentAisFeed installed = CURRENT.get();
		if (installed == null) {
			return null;
		}
		return installed.feeds.current();
	}
}
