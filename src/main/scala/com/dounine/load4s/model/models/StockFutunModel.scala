package com.dounine.load4s.model.models

import com.dounine.load4s.model.types.service.IntervalStatus.IntervalStatus

import java.time.LocalDateTime

object StockFutunModel {

  final case class Info(
      symbol: String,
      interval: IntervalStatus
  ) extends BaseSerializer

}
