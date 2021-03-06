package brs.db.sql

import brs.db.BurstKey
import brs.db.EscrowStore
import brs.db.VersionedEntityTable
import brs.db.upsert
import brs.entity.DependencyProvider
import brs.entity.Escrow
import brs.entity.Escrow.Companion.byteToDecision
import brs.entity.Transaction
import brs.schema.Tables.ESCROW
import brs.schema.Tables.ESCROW_DECISION
import brs.schema.tables.records.EscrowDecisionRecord
import brs.schema.tables.records.EscrowRecord
import org.jooq.DSLContext
import org.jooq.Record

internal class SqlEscrowStore(private val dp: DependencyProvider) : EscrowStore {
    override val escrowDbKeyFactory = object : SqlDbKey.LongKeyFactory<Escrow>(ESCROW.ID) {
        override fun newKey(entity: Escrow): BurstKey {
            return entity.dbKey
        }
    }

    override val escrowTable: VersionedEntityTable<Escrow>
    override val decisionDbKeyFactory = object : SqlDbKey.LinkKeyFactory<Escrow.Decision>("escrow_id", "account_id") {
        override fun newKey(entity: Escrow.Decision): BurstKey {
            return entity.dbKey
        }
    }
    override val decisionTable: VersionedEntityTable<Escrow.Decision>
    override val resultTransactions = mutableListOf<Transaction>()

    init {
        escrowTable = object : SqlVersionedEntityTable<Escrow>(ESCROW, ESCROW.HEIGHT, ESCROW.LATEST, escrowDbKeyFactory, dp) {
            override fun load(ctx: DSLContext, record: Record): Escrow {
                return sqlToEscrow(record)
            }

            override fun save(ctx: DSLContext, entity: Escrow) {
                saveEscrow(ctx, entity)
            }
        }

        decisionTable = object :
            SqlVersionedEntityTable<Escrow.Decision>(ESCROW_DECISION, ESCROW_DECISION.HEIGHT, ESCROW_DECISION.LATEST, decisionDbKeyFactory, dp) {
            override fun load(ctx: DSLContext, record: Record): Escrow.Decision {
                return sqlToDecision(record)
            }

            override fun save(ctx: DSLContext, entity: Escrow.Decision) {
                saveDecision(ctx, entity)
            }
        }
    }

    private fun saveDecision(ctx: DSLContext, decision: Escrow.Decision) {
        val record = EscrowDecisionRecord()
        record.escrowId = decision.escrowId
        record.accountId = decision.accountId
        record.decision = Escrow.decisionToByte(decision.decision).toInt()
        record.height = dp.blockchainService.height
        record.latest = true
        ctx.upsert(record, ESCROW_DECISION.ESCROW_ID, ESCROW_DECISION.ACCOUNT_ID, ESCROW_DECISION.HEIGHT).execute()
    }

    override fun getEscrowTransactionsByParticipant(accountId: Long): Collection<Escrow> {
        val filtered = mutableListOf<Escrow>()
        for (decision in decisionTable.getManyBy(ESCROW_DECISION.ACCOUNT_ID.eq(accountId), 0, -1)) {
            val escrow = escrowTable[escrowDbKeyFactory.newKey(decision.escrowId)]
            if (escrow != null) {
                filtered.add(escrow)
            }
        }
        return filtered
    }

    private fun saveEscrow(ctx: DSLContext, escrow: Escrow) {
        val record = EscrowRecord()
        record.id = escrow.id
        record.senderId = escrow.senderId
        record.recipientId = escrow.recipientId
        record.amount = escrow.amountPlanck
        record.requiredSigners = escrow.requiredSigners
        record.deadline = escrow.deadline
        record.deadlineAction = Escrow.decisionToByte(escrow.deadlineAction).toInt()
        record.height = dp.blockchainService.height
        record.latest = true
        ctx.upsert(record, ESCROW.ID, ESCROW.HEIGHT).execute()
    }

    private fun sqlToDecision(record: Record) = Escrow.Decision(
        decisionDbKeyFactory.newKey(
            record.get(ESCROW_DECISION.ESCROW_ID),
            record.get(ESCROW_DECISION.ACCOUNT_ID)
        ),
        record.get(ESCROW_DECISION.ESCROW_ID),
        record.get(ESCROW_DECISION.ACCOUNT_ID),
        Escrow.byteToDecision(record.get(ESCROW_DECISION.DECISION).toByte()))

    private fun sqlToEscrow(record: Record) = Escrow(
        dp,
        record.get(ESCROW.ID),
        record.get(ESCROW.SENDER_ID),
        record.get(ESCROW.RECIPIENT_ID),
        escrowDbKeyFactory.newKey(record.get(ESCROW.ID)),
        record.get(ESCROW.AMOUNT),
        record.get(ESCROW.REQUIRED_SIGNERS),
        record.get(ESCROW.DEADLINE),
        byteToDecision(record.get(ESCROW.DEADLINE_ACTION).toByte()))

    override fun getDecisions(id: Long?): Collection<Escrow.Decision> {
        return decisionTable.getManyBy(ESCROW_DECISION.ESCROW_ID.eq(id), 0, -1)
    }
}
