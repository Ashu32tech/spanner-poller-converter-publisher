package com.infogain.gcp.poc.poller.service;

import java.util.List;
import java.util.Optional;

import com.google.cloud.spanner.Statement;
import com.infogain.gcp.poc.component.SpannerGateway;
import com.infogain.gcp.poc.poller.entity.PNREntity;
import com.infogain.gcp.poc.poller.entity.PollerCommitTimestampEntity;
import com.infogain.gcp.poc.poller.repository.PNRRepository;
import com.infogain.gcp.poc.poller.repository.PollerCommitTimestampRepository;
import com.infogain.gcp.poc.util.ApplicationConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gcp.pubsub.core.PubSubTemplate;
import org.springframework.stereotype.Service;

import com.google.cloud.Timestamp;
import com.infogain.gcp.poc.component.MessageConverter;

@Slf4j
@Service
public class PnrService {

	@Value("${app.topic.name}")
	private String topicName;

	@Autowired
	private MessageConverter messageConverter;

	@Autowired
	private PubSubTemplate pubSubTemplate;

	@Autowired
	private PollerCommitTimestampRepository pollerCommitTimestampRepository;

	@Autowired
	private PNRRepository pnrRepository;

	@Autowired
	private SpannerGateway spannerGateway;

	private Timestamp getCurrentTimestamp() {
		return spannerGateway.getTimestampRecord(Statement.of(ApplicationConstant.CURRENT_TIMESTAMP_QUERY));
	}

	private List<PNREntity> getPnrDetailToProcess(Timestamp timestamp) {
		List<PNREntity> pnrEntities = null;

		if (timestamp == null) {
			log.info("Last commit timestamp is null in table so getting all pnr records from db");
			pnrEntities = pnrRepository.findAllByOrderByLastUpdateTimestamp();
		} else {
			log.info("Getting all the PNR after timestamp {}", timestamp);
			pnrEntities = pnrRepository.findByLastUpdateTimestampGreaterThanOrderByLastUpdateTimestamp(timestamp);
		}

		pnrEntities = ListUtils.emptyIfNull(pnrEntities);
		log.info("Total PNR Found {}", pnrEntities.size());
		log.info("PNR RECORDS ARE  {}", pnrEntities);

		return pnrEntities;
	}

	private void processMessage(List<PNREntity> pnrEntities) {
		pnrEntities.forEach(pnrEntity -> publishMessage(messageConverter.convert(pnrEntity)));
	}

	private void publishMessage(String message) {
		log.info("publishing message {} to topic {}", message, topicName);
		pubSubTemplate.publish(topicName, message);
		log.info("published message {} to topic {}", message, topicName);
	}

	private Timestamp getLastRecordUpdatedTimestamp(List<PNREntity> pnrEntities) {
		return pnrEntities.get(pnrEntities.size() - 1).getLastUpdateTimestamp();
	}

	private void savePollerCommitTimestamp(Timestamp timestamp, List<PNREntity> pnrEntities) {
		Timestamp lastRecordUpdatedTimestamp = null;
		if (pnrEntities.isEmpty()) {
			lastRecordUpdatedTimestamp = getCurrentTimestamp();
		} else {
			lastRecordUpdatedTimestamp = getLastRecordUpdatedTimestamp(pnrEntities);
		}

		log.info("Going to save the poller last execution time into db {}", lastRecordUpdatedTimestamp);

		if(null == timestamp || !timestamp.equals(lastRecordUpdatedTimestamp)){
			PollerCommitTimestampEntity pollerCommitTimestampEntityLatest = PollerCommitTimestampEntity.builder().lastCommitTimestamp(lastRecordUpdatedTimestamp).build();
			pollerCommitTimestampRepository.save(pollerCommitTimestampEntityLatest);
			log.info("Saved the poller last execution time into db {}", lastRecordUpdatedTimestamp);
		}else{
			log.info("last record updated timestamp already updated. So not inserting again");
		}

	}

	public void execute() {
		Optional<PollerCommitTimestampEntity> pollerCommitTimestampEntityOptional = pollerCommitTimestampRepository.findFirstByOrderByLastCommitTimestamp();
		log.info("last-poller-commit-timestamp={}", pollerCommitTimestampEntityOptional);

		Timestamp timestamp = null;
		if(pollerCommitTimestampEntityOptional.isPresent()){
			timestamp = pollerCommitTimestampEntityOptional.get().getLastCommitTimestamp();
		}

		// get PNREntity list by timestamp
		List<PNREntity> pnrEntities = getPnrDetailToProcess(timestamp);

		try {
			processMessage(pnrEntities);
		} catch (Exception ex) {
			log.info("Got exception while publishing the message", ex);
		} finally {
			savePollerCommitTimestamp(timestamp, pnrEntities);
		}
	}

}