/*
 * Copyright 2020 Ikhun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exmaple.http4s.armeria

import com.linecorp.armeria.common.{HttpRequest, HttpResponse, HttpStatus}
import com.linecorp.armeria.server.{HttpService, ServiceRequestContext, SimpleDecoratingHttpService}

final class NoneShallPass(delegate: HttpService) extends SimpleDecoratingHttpService(delegate) {

  override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
    HttpResponse.of(HttpStatus.FORBIDDEN)
}
