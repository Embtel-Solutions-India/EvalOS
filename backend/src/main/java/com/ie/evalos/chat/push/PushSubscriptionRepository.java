package com.ie.evalos.chat.push;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Browsers that allowed notifications (Unit 57 §6). The subscriber lookup is not brand-filtered: a
 * subscriber id is one brand's row, and every lookup starts from a member row already in the
 * conversation's brand.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

	List<PushSubscription> findBySubscriberKindAndSubscriberId(ParticipantKind kind, UUID subscriberId);

	Optional<PushSubscription> findByEndpoint(String endpoint);

	/**
	 * A shared browser that someone else subscribed: their row goes, and with it (ON DELETE CASCADE) any push
	 * still queued for them, so nothing meant for them reaches this browser's next owner.
	 */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			DELETE FROM push_subscriptions
			 WHERE endpoint = :endpoint AND (subscriber_kind <> :kind OR subscriber_id <> :subscriberId)""")
	int deleteOthersOn(@org.springframework.data.repository.query.Param("endpoint") String endpoint,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("subscriberId") UUID subscriberId);

	/**
	 * Insert or refresh this browser for this person in one statement (2026-10-30). Was find, delete, insert —
	 * and the portal posts the same subscription twice on every load (PushRefresh and PushPrompt), so two of
	 * those raced on the unique endpoint and one answered 409. ON CONFLICT cannot race.
	 */
	@org.springframework.transaction.annotation.Transactional
	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
			INSERT INTO push_subscriptions (id, brand_id, subscriber_kind, subscriber_id, endpoint, p256dh, auth)
			VALUES (:id, :brandId, :kind, :subscriberId, :endpoint, :p256dh, :auth)
			ON CONFLICT (endpoint) DO UPDATE
			   SET brand_id = EXCLUDED.brand_id, subscriber_kind = EXCLUDED.subscriber_kind,
			       subscriber_id = EXCLUDED.subscriber_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth""")
	int upsert(@org.springframework.data.repository.query.Param("id") UUID id,
			@org.springframework.data.repository.query.Param("brandId") UUID brandId,
			@org.springframework.data.repository.query.Param("kind") String kind,
			@org.springframework.data.repository.query.Param("subscriberId") UUID subscriberId,
			@org.springframework.data.repository.query.Param("endpoint") String endpoint,
			@org.springframework.data.repository.query.Param("p256dh") String p256dh,
			@org.springframework.data.repository.query.Param("auth") String auth);
}
