package com.dounine.load4s.model.types.router

import com.dounine.load4s.model.types.router

object ResponseCode extends Enumeration {
  type ResponseCode = Value

  val ok: router.ResponseCode.Value = Value("ok")
  val fail: router.ResponseCode.Value = Value("fail")

}
