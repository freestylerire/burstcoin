package brs

import brs.api.grpc.api.ApiService
import brs.api.grpc.peer.PeerApiService
import brs.api.http.API
import brs.api.http.APITransactionManagerImpl
import brs.at.*
import brs.db.sql.*
import brs.entity.Arguments
import brs.entity.DependencyProvider
import brs.objects.Constants
import brs.objects.Props
import brs.services.BlockchainProcessorService
import brs.services.PropertyService
import brs.services.impl.*
import brs.services.impl.GeneratorServiceImpl
import brs.transaction.type.TransactionType
import brs.util.LoggerConfigurator
import brs.util.Version
import brs.util.logging.safeError
import brs.util.logging.safeInfo
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException
import java.util.*
import kotlin.system.exitProcess

class Burst(properties: Properties, addShutdownHook: Boolean = true) {
    val dp = DependencyProvider()

    /**
     * Class member as opposed to companion member as it needs to be created after LoggerConfigurator.init() is called.
     */
    private val logger = LoggerFactory.getLogger(Burst::class.java)

    init {
        logger.safeInfo { "Initializing Burst Reference Software ($APPLICATION) version $VERSION" }
        dp.propertyService = PropertyServiceImpl(properties)
        validateVersionNotDev(dp.propertyService)

        if (addShutdownHook) Runtime.getRuntime().addShutdownHook(Thread(Runnable { shutdown() }))

        try {
            val startTime = System.currentTimeMillis()
            logger.safeInfo {
                """
**********
SYSTEM INFORMATION
RT: ${System.getProperty("java.runtime.name")}, Version: ${System.getProperty("java.runtime.version")}
VM: ${System.getProperty("java.vm.name")}, Version: ${System.getProperty("java.vm.version")}
OS: ${System.getProperty("os.name")}, Version: ${System.getProperty("os.version")}, Architecture: ${System.getProperty("os.arch")}
**********"""
            }
            Constants.init(dp)
            dp.taskSchedulerService = RxJavaTaskSchedulerService()
            dp.atApi = AtApiPlatformImpl(dp)
            dp.atApiController = AtApiController(dp)
            dp.atController = AtController(dp)
            if (dp.propertyService.get(Props.GPU_ACCELERATION)) {
                dp.oclPocService = OclPocServiceImpl(dp)
            }
            dp.timeService = TimeServiceImpl()
            dp.derivedTableService = DerivedTableServiceImpl()
            dp.statisticsService = StatisticsServiceImpl(dp)
            dp.dbCacheService = DBCacheServiceImpl(dp)
            dp.db = SqlDb(dp)
            dp.blockDb = SqlBlockDb(dp)
            dp.transactionDb = SqlTransactionDb(dp)
            dp.peerDb = SqlPeerDb(dp)
            dp.accountStore = SqlAccountStore(dp)
            dp.aliasStore = SqlAliasStore(dp)
            dp.assetStore = SqlAssetStore(dp)
            dp.assetTransferStore = SqlAssetTransferStore(dp)
            dp.atStore = SqlATStore(dp)
            dp.digitalGoodsStoreStore = SqlDigitalGoodsStoreStore(dp)
            dp.escrowStore = SqlEscrowStore(dp)
            dp.orderStore = SqlOrderStore(dp)
            dp.tradeStore = SqlTradeStore(dp)
            dp.subscriptionStore = SqlSubscriptionStore(dp)
            dp.unconfirmedTransactionService = UnconfirmedTransactionServiceImpl(dp)
            dp.indirectIncomingStore = SqlIndirectIncomingStore(dp)
            dp.blockchainStore = SqlBlockchainStore(dp)
            dp.blockchainService = BlockchainServiceImpl(dp)
            dp.aliasService = AliasServiceImpl(dp)
            dp.fluxCapacitorService = FluxCapacitorServiceImpl(dp)
            dp.transactionTypes = TransactionType.getTransactionTypes(dp)
            dp.blockService = BlockServiceImpl(dp)
            dp.blockchainProcessorService = BlockchainProcessorServiceImpl(dp)
            dp.atConstants = AtConstants(dp)
            dp.economicClusteringService = EconomicClusteringServiceImpl(dp)
            dp.generatorService =
                if (dp.propertyService.get(Props.DEV_TESTNET) && dp.propertyService.get(Props.DEV_MOCK_MINING))
                    GeneratorServiceImpl.MockGeneratorService(dp)
                else
                    GeneratorServiceImpl(dp)
            dp.accountService = AccountServiceImpl(dp)
            dp.transactionService = TransactionServiceImpl(dp)
            dp.transactionProcessorService = TransactionProcessorServiceImpl(dp)
            dp.atService = ATServiceImpl(dp)
            dp.subscriptionService = SubscriptionServiceImpl(dp)
            dp.digitalGoodsStoreService = DigitalGoodsStoreServiceImpl(dp)
            dp.escrowService = EscrowServiceImpl(dp)
            dp.assetExchangeService = AssetExchangeServiceImpl(dp)
            dp.downloadCacheService = DownloadCacheServiceImpl(dp)
            dp.indirectIncomingService = IndirectIncomingServiceImpl(dp)
            dp.feeSuggestionService = FeeSuggestionServiceImpl(dp)
            dp.qrGeneratorService = QRGeneratorServiceImpl()
            dp.deeplinkQRCodeGeneratorService = DeeplinkQRCodeGeneratorServiceImpl()
            dp.deeplinkGeneratorService = DeeplinkGeneratorServiceImpl()
            dp.parameterService = ParameterServiceImpl(dp)
            dp.blockchainProcessorService.addListener(
                BlockchainProcessorService.Event.AFTER_BLOCK_APPLY,
                AT.handleATBlockTransactionsListener(dp)
            )
            dp.blockchainProcessorService.addListener(
                BlockchainProcessorService.Event.AFTER_BLOCK_APPLY,
                DigitalGoodsStoreServiceImpl.expiredPurchaseListener(dp)
            )
            dp.apiTransactionManager = APITransactionManagerImpl(dp)
            dp.peerService = PeerServiceImpl(dp)
            dp.api = API(dp)

            dp.taskSchedulerService.runBeforeStart {
                if (dp.propertyService.get(Props.API_V2_SERVER)) {
                    val hostname = dp.propertyService.get(Props.API_V2_LISTEN)
                    val port =
                        if (dp.propertyService.get(Props.DEV_TESTNET)) dp.propertyService.get(Props.DEV_API_V2_PORT) else dp.propertyService.get(
                            Props.API_V2_PORT
                        )
                    logger.safeInfo { "Starting V2 API Server on port $port" }
                    dp.apiV2Server = ApiService(dp).start(hostname, port)
                } else {
                    logger.safeInfo { "Not starting V2 API Server - it is disabled." }
                }
            }

            dp.taskSchedulerService.runBeforeStart {
                val p2pHostname = dp.propertyService.get(Props.P2P_V2_LISTEN)
                val p2pPort =
                    if (dp.propertyService.get(Props.DEV_TESTNET)) dp.propertyService.get(Props.DEV_P2P_V2_PORT) else dp.propertyService.get(Props.P2P_V2_PORT)
                logger.safeInfo { "Starting V2 P2P Server on port $p2pPort" }
                dp.p2pV2Server = PeerApiService(dp).start(p2pHostname, p2pPort)
            }

            logger.safeInfo { "Starting Task Scheduler" }
            dp.taskSchedulerService.start()

            val currentTime = System.currentTimeMillis()
            logger.safeInfo { "Initialization took ${currentTime - startTime} ms" }
            logger.safeInfo { "$APPLICATION $VERSION started successfully!" }

            if (dp.propertyService.get(Props.DEV_TESTNET)) {
                logger.safeInfo { "RUNNING ON TESTNET - DO NOT USE REAL ACCOUNTS!" }
            }
        } catch (e: Exception) {
            logger.safeError(e) { e.message }
            exitProcess(1)
        }
    }

    private fun validateVersionNotDev(propertyService: PropertyService) {
        if (VERSION.isPrelease && !propertyService.get(Props.DEV_TESTNET)) {
            logger.safeError { "THIS IS A DEVELOPMENT WALLET, PLEASE DO NOT USE THIS" }
            exitProcess(0)
        }
    }

    /**
     * Tries to perform an action that uses a `lateinit` property.
     * If this property is uninitialized, this swallows the exception produced.
     */
    private inline fun ignoreLateinitException(action: () -> Unit) {
        try {
            action()
        } catch (e: UninitializedPropertyAccessException) {
            // Ignore
        }
    }

    fun shutdown() {
        logger.safeInfo { "Shutting down..." }
        ignoreLateinitException {
            dp.api.shutdown()
        }
        ignoreLateinitException {
            dp.apiV2Server.shutdownNow()
        }
        ignoreLateinitException {
            dp.p2pV2Server.shutdownNow()
        }
        ignoreLateinitException {
            dp.peerService.shutdown()
        }
        ignoreLateinitException {
            dp.taskSchedulerService.shutdown()
        }
        ignoreLateinitException {
            dp.db.shutdown()
        }
        ignoreLateinitException {
            dp.dbCacheService.close()
        }
        ignoreLateinitException {
            dp.oclPocService.destroy()
        }
        logger.safeInfo { "$APPLICATION $VERSION stopped." }
        LoggerConfigurator.shutdown()
    }

    companion object {
        val VERSION = Version.parse("v3.0.0-dev")
        const val APPLICATION = "BRS"
        private const val DEFAULT_PROPERTIES_NAME = "brs-default.properties"

        private fun loadProperties(confDirectory: String): Properties {
            val defaultProperties = Properties()

            try {
                FileReader("$confDirectory/$DEFAULT_PROPERTIES_NAME").use { input ->
                    defaultProperties.load(input)
                }
            } catch (e: FileNotFoundException) {
                val configFile = System.getProperty(DEFAULT_PROPERTIES_NAME)

                if (configFile != null) {
                    try {
                        FileReader(configFile).use { fis -> defaultProperties.load(fis) }
                    } catch (e: IOException) {
                        throw Exception("Error loading $DEFAULT_PROPERTIES_NAME from $configFile")
                    }
                } else {
                    throw Exception("$DEFAULT_PROPERTIES_NAME not in classpath and system property $DEFAULT_PROPERTIES_NAME not defined either")
                }
            }

            val properties = Properties(defaultProperties)
            try {
                FileReader("$confDirectory/brs.properties").use { input ->
                    properties.load(input)
                }
            } catch (e: IOException) {
                if (e !is FileNotFoundException) {
                    throw Exception("Error loading brs.properties", e)
                }
            }

            return properties
        }

        @JvmStatic
        fun main(args: Array<String>) {
            init(Arguments.parse(args))
        }

        fun init(arguments: Arguments, addShutdownHook: Boolean = true): Burst {
            LoggerConfigurator.init(arguments.configDirectory)
            return Burst(loadProperties(arguments.configDirectory), addShutdownHook)
        }
    }
}
