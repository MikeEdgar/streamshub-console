import { getTopic } from "@/api/topics";
import { KafkaTopicParams } from "@/app/[locale]/kafka/[kafkaId]/topics/kafkaTopic.params";
import { PageSection } from "@/libs/patternfly/react-core";
import { ConfigTable } from "./ConfigTable";

export default async function TopicConfiguration({
  params: { kafkaId, topicId },
}: {
  params: KafkaTopicParams;
}) {
  const topic = await getTopic(kafkaId, topicId);
  return (
    <PageSection isFilled={true}>
      <ConfigTable topic={topic} />
    </PageSection>
  );
}