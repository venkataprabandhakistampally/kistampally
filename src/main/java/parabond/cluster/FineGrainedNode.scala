/*
 * Copyright (c) Ron Coleman
 * See CONTRIBUTORS.TXT for a full list of copyright holders.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Scaly Project nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE DEVELOPERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package parabond.cluster

import org.apache.log4j.Logger
import parabond.casa.MongoDbObject
import parabond.entry.SimpleBond
import parabond.util.Constant.{NUM_PORTFOLIOS, PORTF_NUM}
import parabond.util.{Helper, JavaMongoHelper, Job, MongoHelper, Result}
import parabond.value.SimpleBondValuator
import parascale.util.getPropertyOrElse

import scala.collection.parallel.CollectionConverters._

/**
  * Runs a fine grain node which retrieves the portfolios in random order and prices the bonds as
  * a parallel collection.
  */
object FineGrainedNode extends App {
  val LOG = Logger.getLogger(getClass)

  // Hushes mongo
  JavaMongoHelper.hush()

  val seed = getPropertyOrElse("seed",0)
  val size = getPropertyOrElse("size", NUM_PORTFOLIOS)
  val n = getPropertyOrElse("n", PORTF_NUM)
  val begin = getPropertyOrElse("begin", 1)

  val checkIds = checkReset(n)

  val analysis = new FineGrainedNode(Partition(n=n, begin=begin)) analyze

  report(LOG, analysis, checkIds)
}

/**
  * Prices one bond per core then rolls these prices into the portfolio price.
  */
class FineGrainedNode(partition: Partition) extends BasicNode(partition) {
  /**
    * Price a portfolio and updates mongo accordingly.
    * @param task Portfolio id to price
    * @return Valuation as job result
    */
  override def price(task: Job): Job = {
    // Value each bond in the portfolio
    val t0 = System.nanoTime

    // Retrieve the portfolio
    val portfId = task.portfId
    assert(portfId>0, s"invalid portfId=$portfId")

    val portfsQuery = MongoDbObject("id" -> portfId)

    val portfsCursor = MongoHelper.portfolioCollection.find(portfsQuery)
    assert(portfsCursor.iterator().hasNext, s"query found no bonds for portfId=$portfId")

    // Get the bonds in the portfolio
    val bids = MongoHelper.asList(portfsCursor,"instruments")
    assert(bids.size>0, s"bond ids empty for portId=$portfId")

    val bondIds = for(i <- 0 until bids.size) yield new Job(bids(i),null,null)
    assert(bondIds.length>0, s"no jobs created for portfId=$portfId")

    val output = bondIds.par.map { bondId =>
      // Get the bond from the bond collection
      val bondQuery = MongoDbObject("id" -> bondId.portfId)

      val bondCursor = MongoHelper.bondCollection.find(bondQuery)

      val bond = MongoHelper.asBond(bondCursor)

      val valuator = new SimpleBondValuator(bond, Helper.yieldCurve)

      val price = valuator.price
      assert(price>0, s"invalid price for portfId=$portfId")

      new SimpleBond(bond.id,bond.coupon,bond.freq,bond.tenor,price)
    }.reduce(sum)

    MongoHelper.updatePrice(task.portfId,output.maturity)

    val t1 = System.nanoTime

    new Job(task.portfId, null, Result(task.portfId, output.maturity, bondIds.size, t0, t1))
  }

  /**
    * Reduces to simple bond prices.
    * @param a Bond a
    * @param b Bond b
    * @return Reduced bond price
    */
  def sum(a: SimpleBond, b:SimpleBond) : SimpleBond = {
    new SimpleBond(0,0,0,0,a.maturity+b.maturity)
  }
}
