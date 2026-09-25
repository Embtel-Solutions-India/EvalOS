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
}
