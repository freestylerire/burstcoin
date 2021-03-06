package brs.db.sql

import brs.db.BurstKey
import brs.db.SubscriptionStore
import brs.db.VersionedEntityTable
import brs.db.upsert
import brs.entity.DependencyProvider
import brs.entity.Subscription
import brs.schema.Tables.SUBSCRIPTION
import brs.schema.tables.records.SubscriptionRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record

internal class SqlSubscriptionStore(private val dp: DependencyProvider) : SubscriptionStore {
    override val subscriptionDbKeyFactory = object : SqlDbKey.LongKeyFactory<Subscription>(SUBSCRIPTION.ID) {
        override fun newKey(entity: Subscription): BurstKey {
            return entity.dbKey
        }
    }

    override val subscriptionTable: VersionedEntityTable<Subscription>

    init {
        subscriptionTable =
            object : SqlVersionedEntityTable<Subscription>(
                SUBSCRIPTION,
                SUBSCRIPTION.HEIGHT,
                SUBSCRIPTION.LATEST,
                subscriptionDbKeyFactory,
                dp
            ) {
                override val defaultSort = listOf(
                    table.field("time_next", Int::class.java).asc(),
                    table.field("id", Long::class.java).asc()
                )

                override fun load(ctx: DSLContext, record: Record): Subscription {
                    return sqlToSubscription(record)
                }

                override fun save(ctx: DSLContext, entity: Subscription) {
                    saveSubscription(ctx, entity)
                }
            }
    }

    private fun getByParticipantClause(id: Long): Condition {
        return SUBSCRIPTION.SENDER_ID.eq(id).or(SUBSCRIPTION.RECIPIENT_ID.eq(id))
    }

    private fun getUpdateOnBlockClause(timestamp: Int): Condition {
        return SUBSCRIPTION.TIME_NEXT.le(timestamp)
    }

    override fun getSubscriptionsByParticipant(accountId: Long): Collection<Subscription> {
        return subscriptionTable.getManyBy(getByParticipantClause(accountId), 0, -1)
    }

    override fun getIdSubscriptions(accountId: Long): Collection<Subscription> {
        return subscriptionTable.getManyBy(SUBSCRIPTION.SENDER_ID.eq(accountId), 0, -1)
    }

    override fun getSubscriptionsToId(accountId: Long): Collection<Subscription> {
        return subscriptionTable.getManyBy(SUBSCRIPTION.RECIPIENT_ID.eq(accountId), 0, -1)
    }

    override fun getUpdateSubscriptions(timestamp: Int): Collection<Subscription> {
        return subscriptionTable.getManyBy(getUpdateOnBlockClause(timestamp), 0, -1)
    }

    private fun saveSubscription(ctx: DSLContext, subscription: Subscription) {
        val record = SubscriptionRecord()
        record.id = subscription.id
        record.senderId = subscription.senderId
        record.recipientId = subscription.recipientId
        record.amount = subscription.amountPlanck
        record.frequency = subscription.frequency
        record.timeNext = subscription.timeNext
        record.height = dp.blockchainService.height
        record.latest = true
        ctx.upsert(
            record,
            SUBSCRIPTION.ID,
            SUBSCRIPTION.SENDER_ID,
            SUBSCRIPTION.RECIPIENT_ID,
            SUBSCRIPTION.AMOUNT,
            SUBSCRIPTION.FREQUENCY,
            SUBSCRIPTION.TIME_NEXT,
            SUBSCRIPTION.HEIGHT,
            SUBSCRIPTION.LATEST
        ).execute()
    }

    private fun sqlToSubscription(record: Record) = Subscription(
        record.get(SUBSCRIPTION.SENDER_ID),
        record.get(SUBSCRIPTION.RECIPIENT_ID),
        record.get(SUBSCRIPTION.ID),
        record.get(SUBSCRIPTION.AMOUNT),
        record.get(SUBSCRIPTION.FREQUENCY),
        record.get(SUBSCRIPTION.TIME_NEXT),
        subscriptionDbKeyFactory.newKey(record.get(SUBSCRIPTION.ID)))
}
