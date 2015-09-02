package scorex.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import org.joda.time.DateTime
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.consensus.ConsensusModule
import scorex.crypto.SigningFunctionsImpl
import scorex.transaction.TransactionModule
import scorex.utils.ScorexLogging

import scala.util.{Failure, Try}


trait Block {
  type ConsensusDataType
  type TransactionDataType

  val versionField: ByteBlockField
  val timestampField: LongBlockField
  val referenceField: BlockIdField
  val consensusDataField: BlockField[ConsensusDataType]
  val transactionDataField: BlockField[TransactionDataType]
  val signerDataField: SignerDataBlockField


  implicit val consensusModule: ConsensusModule[ConsensusDataType]
  implicit val transactionModule: TransactionModule[TransactionDataType]

  // Some block characteristic which is uniq e.g. hash or signature(if timestamp is included there).
  // Used in referencing
  val uniqueId: Block.BlockId

  lazy val transactions = transactionModule.transactions(this)

  lazy val json =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      transactionDataField.json ++
      signerDataField.json

  lazy val bytes = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      txBytes ++
      signerDataField.bytes
  }

  def isValid = {
    val history = transactionModule.history
    val state = transactionModule.state

    consensusModule.isValid(this, history, state) &&
      transactionModule.isValid(this) &&
      history.contains(referenceField.value) &&
      SigningFunctionsImpl.verify(signerDataField.value.signature,
        bytes.dropRight(SigningFunctionsImpl.SignatureLength),
        signerDataField.value.generator.publicKey)
  }
}


object Block extends ScorexLogging {
  type BlockId = Array[Byte]

  val BlockIdLength = SigningFunctionsImpl.SignatureLength

  def parse[CDT, TDT](bytes: Array[Byte])
                     (implicit consModule: ConsensusModule[CDT],
                      transModule: TransactionModule[TDT]): Try[Block] = Try {

    require(consModule != null)
    require(transModule != null)

    var position = 1

    val version = bytes.head

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position += 8

    val reference = bytes.slice(position, position + Block.BlockIdLength)
    position += BlockIdLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val consBlockField = consModule.parseBlockData(cBytes)
    position += cBytesLength


    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transModule.parseBlockData(tBytes)
    position += tBytesLength

    val genPK = bytes.slice(position, position + SigningFunctionsImpl.KeyLength)
    position += SigningFunctionsImpl.KeyLength

    val signature = bytes.slice(position, position + SigningFunctionsImpl.SignatureLength)

    new Block {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override val transactionDataField: BlockField[TransactionDataType] = txBlockField

      override implicit val consensusModule: ConsensusModule[ConsensusDataType] = consModule
      override implicit val transactionModule: TransactionModule[TransactionDataType] = transModule

      override val versionField: ByteBlockField = ByteBlockField("version", version)
      override val referenceField: BlockIdField = BlockIdField("reference", reference)
      override val signerDataField: SignerDataBlockField =
        SignerDataBlockField("signature", SignerData(new PublicKeyAccount(genPK), signature))

      override val consensusDataField: BlockField[ConsensusDataType] = consBlockField

      //todo: wrong!
      override val uniqueId: BlockId = signature

      override val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
    }
  }.recoverWith { case t: Throwable =>
    log.error("Error when parsing block", t)
    t.printStackTrace()
    Failure(t)
  }

  def build[CDT, TDT](version: Byte,
                      timestamp: Long,
                      reference: BlockId,
                      consensusData: CDT,
                      transactionData: TDT,
                      generator: PublicKeyAccount,
                      signature: Array[Byte])
                     (implicit consModule: ConsensusModule[CDT],
                      transModule: TransactionModule[TDT]): Block = {
    new Block {
      override type ConsensusDataType = CDT
      override type TransactionDataType = TDT

      override implicit val transactionModule: TransactionModule[TDT] = transModule
      override implicit val consensusModule: ConsensusModule[CDT] = consModule

      override val versionField: ByteBlockField = ByteBlockField("version", version)

      override val transactionDataField: BlockField[TDT] = transModule.formBlockData(transactionData)

      override val referenceField: BlockIdField = BlockIdField("reference", reference)
      override val signerDataField: SignerDataBlockField = SignerDataBlockField("signature", SignerData(generator, signature))
      override val consensusDataField: BlockField[CDT] = consensusModule.formBlockData(consensusData)

      override val uniqueId: BlockId = signature //todo:wrong

      override val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
    }
  }

  def buildAndSign[CDT, TDT](version: Byte,
                             timestamp: Long,
                             reference: BlockId,
                             consensusData: CDT,
                             transactionData: TDT,
                             signer: PrivateKeyAccount)
                            (implicit consModule: ConsensusModule[CDT],
                             transModule: TransactionModule[TDT]): Block = {
    val nonSignedBlock = build(version, timestamp, reference, consensusData, transactionData, signer, Array())
    val toSign = nonSignedBlock.bytes
    val signature = SigningFunctionsImpl.sign(signer, toSign)
    build(version, timestamp, reference, consensusData, transactionData, signer, signature)
  }

  def genesis[CDT, TDT]()(implicit consModule: ConsensusModule[CDT],
                          transModule: TransactionModule[TDT]): Block = new Block {
    override type ConsensusDataType = CDT
    override type TransactionDataType = TDT

    override implicit val transactionModule: TransactionModule[TDT] = transModule
    override implicit val consensusModule: ConsensusModule[CDT] = consModule

    override val versionField: ByteBlockField = ByteBlockField("version", 1)
    override val transactionDataField: BlockField[TDT] = transactionModule.genesisData
    override val referenceField: BlockIdField = BlockIdField("reference", Array.fill(BlockIdLength)(0: Byte))
    override val consensusDataField: BlockField[CDT] = consensusModule.genesisData
    override val uniqueId: BlockId = Array.fill(BlockIdLength)(0: Byte)
    override val timestampField: LongBlockField = LongBlockField("timestamp", new DateTime(System.currentTimeMillis()).toDateMidnight.getMillis)

    override val signerDataField: SignerDataBlockField =
      new SignerDataBlockField("signature", SignerData(new PublicKeyAccount(Array.fill(32)(0)), Array.fill(SigningFunctionsImpl.SignatureLength)(0)))
  }
}
