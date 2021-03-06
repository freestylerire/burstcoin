package brs.transaction.type.coloredCoins

import brs.entity.Account
import brs.entity.DependencyProvider
import brs.entity.Transaction

abstract class OrderCancellation(dp: DependencyProvider) : ColoredCoins(dp) {
    override fun applyAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) = true

    override fun undoAttachmentUnconfirmed(transaction: Transaction, senderAccount: Account) = Unit

    override fun hasRecipient() = false
}
