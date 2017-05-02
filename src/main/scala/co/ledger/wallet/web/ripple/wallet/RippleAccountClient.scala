package co.ledger.wallet.web.ripple.wallet

import co.ledger.wallet.core.concurrent.{AbstractAsyncCursor, AsyncCursor}
import co.ledger.wallet.core.utils.DerivationPath
import co.ledger.wallet.core.wallet.ripple.Wallet.{NewOperationEvent, StartSynchronizationEvent}
import co.ledger.wallet.core.wallet.ripple._
import co.ledger.wallet.core.wallet.ripple.api.ApiAccountRestClient
import co.ledger.wallet.core.wallet.ripple.database.{AccountRow, DatabaseBackedAccountClient}
import co.ledger.wallet.web.ripple.core.net.JQHttpClient
import co.ledger.wallet.web.ripple.wallet.database.RippleDatabase

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.timers.setTimeout
import scala.util.{Failure, Success}

/**
  * Created by alix on 4/13/17.
  */
class RippleAccountClient(walletClient: RippleWalletClient,
                         row: AccountRow
                         ) extends Account {

  private def init(): Future[Unit] = {
    synchronize()
  }

  override def index: Int = row.index

  override def wallet: Wallet = walletClient.asInstanceOf[Wallet]

  override def synchronize(): Future[Unit] = {
    println("synchronize called")
    _synchronizationFuture.getOrElse({
      _synchronizationFuture = Some(
        _api.balance() flatMap {(bal) =>
          walletClient.putAccount(new AccountRow(row.index, row.rippleAccount, bal))
          walletClient.lastOperationDate() flatMap {(last) =>
            println("last", last)
            _api.transactions(last) map { (transactions) =>
              for (transaction <- transactions) {
                walletClient.putTransaction(transaction)
                walletClient.putOperation(new AccountRow(row.index, row
                  .rippleAccount, bal), transaction) map {(_) =>
                  println("new transaction event")
                  walletClient.eventEmitter.emit(NewOperationEvent(this, walletClient.OperationModel(new AccountRow(row.index, row
                    .rippleAccount, bal), transaction).proxy(this, transaction)))
                }
              }
            }
          }
        } andThen {
          case all =>
            println("synchronized over")
            _synchronizationFuture = None
        }
      )
      _synchronizationFuture.get
    })
  }

  override def isSynchronizing(): Future[Boolean] = Future.successful(
    _synchronizationFuture.nonEmpty
  )

  override def operations(limit: Int, batchSize: Int): Future[AsyncCursor[Operation]] = {
    walletClient.countOperations(index) map {(c) =>
      new AbstractAsyncCursor[Operation](global, batchSize) {
        override protected def performQuery(from: Int, to: Int): Future[Array[Operation]] = {
          walletClient.queryOperations(from, to, RippleAccountClient.this)
        }
        override def count: Int = if (limit == -1 || limit > c) c.toInt else limit

        override def requery(): Future[AsyncCursor[Operation]] =
          operations(limit, batchSize)
      }
    }
  }

  override def rippleAccount(): Future[RippleAccount] =
    Future.successful(RippleAccount(row.rippleAccount))

  override def rippleAccountDerivationPath(): Future[DerivationPath] =
    Future.successful(DerivationPath(s"44'/${walletClient
      .bip44CoinType}'/$index'/0/0"))

  override def hashCode(): Int = super.hashCode()

  override def balance(): Future[XRP] = {
    walletClient.queryAccount(index) map {(account) =>
      account.balance
    } recover {
      case walletClient.BadAccountIndex() => XRP.Zero
    }
  }

  private val _api = new ApiAccountRestClient(JQHttpClient.xrpInstance,row)
  private var _synchronizationFuture: Option[Future[Unit]] = None
}


