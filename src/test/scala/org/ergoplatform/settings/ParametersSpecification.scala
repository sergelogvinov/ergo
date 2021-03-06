package org.ergoplatform.settings

import org.ergoplatform.modifiers.history.Extension
import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.utils.ErgoPropertyTest
import scorex.crypto.authds.ADDigest

import scala.language.implicitConversions

class ParametersSpecification extends ErgoPropertyTest {

  import Parameters._

  private val headerId = scorex.util.bytesToId(Array.fill(32)(0: Byte))

  private val votingEpochLength = 2

  override implicit val votingSettings: VotingSettings =
    VotingSettings(votingEpochLength, softForkEpochs = 2, activationEpochs = 3)

  private implicit def toExtension(p: Parameters): Extension = p.toExtensionCandidate(Seq.empty).toExtension(headerId)

  property("extension processing") {
    val constants = stateConstants.copy(
      settings = settings.copy(
        chainSettings = settings.chainSettings.copy(voting = votingSettings)
      )
    )
    val ctx = ErgoStateContext.empty(constants)
    val chain = genChain(votingEpochLength * 4).map { b =>
      b.copy(extension = b.extension.copy(fields = LaunchParameters.toExtensionCandidate(Seq.empty).fields))
    }
    val validChain = chain.init
    val lastBlock = chain.last
    val invalidExtBlock1 = { // extension does not contain all required params
      lastBlock.copy(extension = lastBlock.extension.copy(
        fields = Seq(Array(0: Byte, 1: Byte) -> Array.fill(4)(2: Byte)))
      )
    }
    val invalidExtBlock2 = { // extension contains redundant parameter
      lastBlock.copy(extension = lastBlock.extension.copy(
        fields = LaunchParameters.toExtensionCandidate(Seq.empty).fields :+ Array(0: Byte, 99: Byte) -> Array.fill(4)(2: Byte))
      )
    }
    val invalidExtBlock3 = { // extension does not contain params at all
      lastBlock.copy(extension = lastBlock.extension.copy(fields = Seq()))
    }
    val validCtx = validChain.foldLeft(ctx)((acc, mod) => acc.appendFullBlock(mod, votingSettings).get)
    validCtx.appendFullBlock(invalidExtBlock1, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(invalidExtBlock2, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(invalidExtBlock3, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(lastBlock, votingSettings) shouldBe 'success
  }

  //Simple checks for votes in header could be found also in NonVerifyADHistorySpecification("Header votes")
  property("simple voting - start - conditions") {
    val kInit = 1000000

    val p: Parameters = Parameters(2, Map(StorageFeeFactorIncrease -> kInit, BlockVersion -> 0))
    val vr: VotingData = VotingData.empty
    val esc = new ErgoStateContext(Seq(), ADDigest @@ Array.fill(33)(0: Byte), p, vr)
    val votes = Array(StorageFeeFactorIncrease, NoParameter, NoParameter)
    val h = defaultHeaderGen.sample.get.copy(height = 2, votes = votes, version = 0: Byte)
    val esc2 = esc.process(h, p).get

    //no quorum gathered - no parameter change
    val he = defaultHeaderGen.sample.get.copy(votes = Array.fill(3)(NoParameter), version = 0: Byte)
    val esc30 = esc2.process(he, p).get
    val esc40 = esc30.process(he, p).get
    esc40.currentParameters.storageFeeFactor shouldBe kInit

    //quorum gathered - parameter change
    val esc31 = esc2.process(h.copy(height = 3), p).get
    esc31.votingData.epochVotes.find(_._1 == StorageFeeFactorIncrease).get._2 shouldBe 2

    val p4 = Parameters(4, Map(StorageFeeFactorIncrease -> (kInit + Parameters.StorageFeeFactorStep), BlockVersion -> 0))
    val esc41 = esc31.process(he.copy(height = 4), p4).get
    esc41.currentParameters.storageFeeFactor shouldBe (kInit + Parameters.StorageFeeFactorStep)
  }

  /**
    * A test which is ensuring that approved soft-fork activates properly.
    * For the test, we have:
    *   - epoch length is about 2 blocks
    *   - 2 epochs to vote
    *   - 3 epochs to activate the fork
    *
    * So the fork would be activated only if 4 votes out of 4 are for it.
    */
  property("soft fork - w. activation") {
    val p: Parameters = Parameters(1, Map(BlockVersion -> 0))
    val vr: VotingData = VotingData.empty
    val esc1 = new ErgoStateContext(Seq(), ADDigest @@ Array.fill(33)(0: Byte), p, vr)
    val forkVote = Array(SoftFork, NoParameter, NoParameter)
    val emptyVotes = Array(NoParameter, NoParameter, NoParameter)

    // Soft-fork vote is proposed @ height == 2
    val h2 = defaultHeaderGen.sample.get.copy(votes = forkVote, version = 0: Byte, height = 2)
    val expectedParameters2 = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 0))
    val esc2 = esc1.process(h2, expectedParameters2).get
    esc2.currentParameters.softForkStartingHeight.get shouldBe 2

    // wrong parameters: started voting is not reflected in the parameters
    val wrongParameters2 = Parameters(2, Map(BlockVersion -> 0))
    esc1.process(h2, wrongParameters2).isFailure shouldBe true

    // wrong parameters: voting just started, but collected votes is more than 0
    val wrongParameters2a = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 1, BlockVersion -> 0))
    esc1.process(h2, wrongParameters2a).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters2b = Parameters(2, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 0, BlockVersion -> 0))
    esc1.process(h2, wrongParameters2b).isFailure shouldBe true

    // wrong parameters: incorrect block version
    val wrongParameters2c = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 1))
    esc1.process(h2, wrongParameters2c).isFailure shouldBe true


    // voting for the fork @ height == 3
    val h3 = h2.copy(height = 3)
    val esc3 = esc2.process(h3, expectedParameters2).get
    esc3.currentParameters.softForkStartingHeight.get shouldBe 2


    // voting for the fork @ height == 4
    // new epoch is starting, thus the block should contain number of votes for the fork collected in the previous epoch
    val h4 = h3.copy(height = 4)
    val expectedParameters4 = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 2, BlockVersion -> 0))
    val esc4 = esc3.process(h4, expectedParameters4).get
    esc4.currentParameters.softForkStartingHeight.get shouldBe 2
    esc4.currentParameters.softForkVotesCollected.get shouldBe 2

    // wrong parameters: voting is not reflected in the parameters
    val wrongParameters4 = Parameters(4, Map(BlockVersion -> 0))
    esc3.process(h4, wrongParameters4).isFailure shouldBe true

    // wrong parameters: collected votes value is wrong
    val wrongParameters4a = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0))
    esc3.process(h4, wrongParameters4a).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters4b = Parameters(4, Map(SoftForkStartingHeight -> 3, SoftForkVotesCollected -> 2, BlockVersion -> 0))
    esc3.process(h4, wrongParameters4b).isFailure shouldBe true

    // voting for the fork @ height == 5
    val h5 = h4.copy(height = 5)
    val esc5 = esc4.process(h5, expectedParameters4).get

    // voting is finished, and we check collected votes @ height == 6
    val h6 = h5.copy(height = 6, votes = emptyVotes)
    val expectedParameters6 = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 0))
    val esc6 = esc5.process(h6, expectedParameters6).get

    // wrong parameters: voting is not reflected in the parameters
    val wrongParameters6 = Parameters(6, Map(BlockVersion -> 0))
    esc5.process(h6, wrongParameters6).isFailure shouldBe true

    // wrong parameters: collected votes value is wrong
    val wrongParameters6a = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 5, BlockVersion -> 0))
    esc5.process(h6, wrongParameters6a).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters6b = Parameters(6, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 2, BlockVersion -> 0))
    esc5.process(h6, wrongParameters6b).isFailure shouldBe true

    // voting for soft-fork is prohibited @ height == 6
    val h6w = h5.copy(height = 6)
    esc5.process(h6w, expectedParameters6).isSuccess shouldBe false

    val esc11 = (7 to 11).foldLeft(esc6) { case (esc, i) =>
      // voting for soft-fork is prohibited during activation period
      val hw = h6.copy(height = i, votes = forkVote)
      esc.process(hw, expectedParameters6).isFailure shouldBe true


      val h = h6.copy(height = i)

      // wrong parameters checks
      if (i % 2 == 0) {
        // wrong parameters: voting is not reflected in the parameters
        val wrongParametersI = Parameters(i, Map(BlockVersion -> 0))
        esc.process(h, wrongParametersI).isFailure shouldBe true

        // wrong parameters: collected votes value is wrong
        val wrongParametersIa = Parameters(i, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 5, BlockVersion -> 0))
        esc.process(h, wrongParametersIa).isFailure shouldBe true

        // wrong parameters: invalid starting height
        val wrongParametersIb = Parameters(i, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 4, BlockVersion -> 0))
        esc.process(h, wrongParametersIb).isFailure shouldBe true

        // wrong parameters: invalid block version
        val wrongParametersIc = Parameters(i, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1))
        esc.process(h, wrongParametersIc).isFailure shouldBe true
      }

      esc.process(h, expectedParameters6).get
    }

    // activation period done @ height = 12, block version is increased
    val h12 = h6.copy(height = 12, version = 1: Byte)
    val expectedParameters12 = Parameters(12, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1))
    val esc12 = esc11.process(h12, expectedParameters12).get

    // vote for soft-fork @ activation height
    val h12w = h12.copy(votes = forkVote)
    esc11.process(h12w, expectedParameters12).isFailure shouldBe true

    val h13 = h12.copy(height = 13)
    val esc13 = esc12.process(h13, expectedParameters12).get

    // vote for soft-fork is prohibited before next epoch after activation height
    val h13w = h13.copy(votes = forkVote)
    esc12.process(h13w, expectedParameters12).isFailure shouldBe true

    // voting for soft-fork is possible on the first block of the next epoch after activation height
    val h14 = h13.copy(height = 14, votes = forkVote)
    val expectedParameters14 = Parameters(14, Map(SoftForkStartingHeight -> 14, SoftForkVotesCollected -> 0, BlockVersion -> 1))
    val esc14 = esc13.process(h14, expectedParameters14).get

    // next epoch after activation height - soft-fork related parameters are cleared
    val h14b = h13.copy(height = 14, votes = emptyVotes)
    val expectedParameters14a = Parameters(14, Map(BlockVersion -> 1))
    val esc14b = esc13.process(h14b, expectedParameters14a).get

    //wrong parameters: no vote for the fork, but parameters are there
    val wrongParameters14 = Parameters(14, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1))
    esc13.process(h14b, wrongParameters14).isFailure shouldBe true
  }

  /**
    * A vote for a soft-fork which is not gathering enough votes to be activated.
    * The voting settings are :
    *   - epoch length is about 2 blocks
    *   - 2 epochs to vote
    *   - 3 epochs to activate the fork
    *
    */
  property("soft fork - unsuccessful voting") {
    val p: Parameters = Parameters(1, Map(BlockVersion -> 0))
    val vr: VotingData = VotingData.empty
    val esc1 = new ErgoStateContext(Seq(), ADDigest @@ Array.fill(33)(0: Byte), p, vr)
    val forkVote = Array(SoftFork, NoParameter, NoParameter)
    val emptyVotes = Array(NoParameter, NoParameter, NoParameter)

    // Soft-fork vote is proposed @ height == 2
    val h2 = defaultHeaderGen.sample.get.copy(votes = forkVote, version = 0: Byte, height = 2)
    val expectedParameters2 = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 0))
    val esc2 = esc1.process(h2, expectedParameters2).get
    esc2.currentParameters.softForkStartingHeight.get shouldBe 2

    // voting for the fork @ height == 3
    val h3 = h2.copy(height = 3)
    val esc3 = esc2.process(h3, expectedParameters2).get
    esc3.currentParameters.softForkStartingHeight.get shouldBe 2

    // voting for the fork @ height == 4
    val h4 = h3.copy(height = 4)
    val expectedParameters4 = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 2, BlockVersion -> 0))
    val esc4 = esc3.process(h4, expectedParameters4).get
    esc4.currentParameters.softForkStartingHeight.get shouldBe 2
    esc4.currentParameters.softForkVotesCollected.get shouldBe 2

    // no vote for the fork @ height == 5, so only soft-fork proposal has gathered 75% only
    val h5 = h4.copy(height = 5, votes = emptyVotes)
    val esc5 = esc4.process(h5, expectedParameters4).get

    // first epoch after the voting done, data should still be in the block
    val h6 = h5.copy(height = 6)
    val expectedParameters6 = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0))
    val esc6 = esc5.process(h6, expectedParameters6).get

    // in the first epoch after the voting done, it is prohibited to propose a new voting for a fork
    val h6w = h5.copy(height = 6, votes = forkVote)
    esc5.process(h6w, expectedParameters6).isFailure shouldBe true

    val h7 = h6.copy(height = 7)
    val esc7 = esc6.process(h7, expectedParameters6).get

    //... also prohibited to vote for a fork during the first epoch after the voting done
    val h7w = h6.copy(height = 7, votes = forkVote)
    esc6.process(h7w, expectedParameters6).isFailure shouldBe true

    // a new fork voting is proposed on the first block of the second epoch after voting (which has not gathered enough)
    val h8 = h7.copy(height = 8, votes = forkVote)
    val expectedParameters8 = Parameters(8, Map(SoftForkStartingHeight -> 8, SoftForkVotesCollected -> 0, BlockVersion -> 0))
    val esc8 = esc7.process(h8, expectedParameters8).get

    // on the second epoch after voting (not fathered enough) parameters are to be cleared,
    // and block version to be the same
    val h8e = h7.copy(height = 8, votes = emptyVotes)
    val expectedParameters8e = Parameters(8, Map(BlockVersion -> 0))
    val esc8e = esc7.process(h8e, expectedParameters8e).get

    // parameters are not cleared
    val wrongParameters8 = Parameters(8, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0))
    esc7.process(h8e, wrongParameters8).isFailure shouldBe true
  }

}
