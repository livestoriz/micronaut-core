/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.http.server.netty;

import com.typesafe.netty.http.StreamedHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import org.particleframework.context.BeanLocator;
import org.particleframework.core.annotation.Internal;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.async.subscriber.CompletionAwareSubscriber;
import org.particleframework.core.convert.ConversionService;
import org.particleframework.core.io.buffer.ByteBuffer;
import org.particleframework.core.type.Argument;
import org.particleframework.http.HttpMethod;
import org.particleframework.http.HttpRequest;
import org.particleframework.http.HttpResponse;
import org.particleframework.http.*;
import org.particleframework.http.codec.CodecException;
import org.particleframework.http.codec.MediaTypeCodec;
import org.particleframework.http.codec.MediaTypeCodecRegistry;
import org.particleframework.http.filter.HttpFilter;
import org.particleframework.http.filter.HttpServerFilter;
import org.particleframework.http.server.HttpServerConfiguration;
import org.particleframework.http.server.binding.RequestBinderRegistry;
import org.particleframework.http.server.codec.TextPlainCodec;
import org.particleframework.http.server.cors.CorsFilter;
import org.particleframework.http.server.exceptions.ExceptionHandler;
import org.particleframework.http.server.netty.configuration.NettyHttpServerConfiguration;
import org.particleframework.http.server.netty.multipart.NettyPart;
import org.particleframework.inject.qualifiers.Qualifiers;
import org.particleframework.runtime.executor.ExecutorSelector;
import org.particleframework.web.router.RouteMatch;
import org.particleframework.web.router.Router;
import org.particleframework.web.router.UriRouteMatch;
import org.particleframework.web.router.exceptions.UnsatisfiedRouteException;
import org.particleframework.web.router.qualifier.ConsumesMediaTypeQualifier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Internal implementation of the {@link ChannelInboundHandler} for Particle
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
class RoutingInBoundHandler extends SimpleChannelInboundHandler<HttpRequest<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingInBoundHandler.class);

    private final Router router;
    private final ExecutorSelector executorSelector;
    private final ExecutorService ioExecutor;
    private final BeanLocator beanLocator;
    private final NettyHttpServerConfiguration serverConfiguration;
    private final RequestArgumentSatisfier requestArgumentSatisfier;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;

    RoutingInBoundHandler(
            BeanLocator beanLocator,
            Router router,
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            NettyHttpServerConfiguration serverConfiguration,
            RequestBinderRegistry binderRegistry,
            ExecutorSelector executorSelector,
            ExecutorService ioExecutor) {
        this.mediaTypeCodecRegistry = mediaTypeCodecRegistry;
        this.beanLocator = beanLocator;
        this.ioExecutor = ioExecutor;
        this.executorSelector = executorSelector;
        this.router = router;
        this.requestArgumentSatisfier = new RequestArgumentSatisfier(binderRegistry);
        this.serverConfiguration = serverConfiguration;
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        super.channelReadComplete(ctx);
        NettyHttpRequest request = NettyHttpRequest.get(ctx);
        if (request != null) {
            request.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        ctx.flush();
        NettyHttpRequest request = NettyHttpRequest.get(ctx);
        if (request != null) {
            request.release();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest<?> request) throws Exception {
        HttpMethod httpMethod = request.getMethod();
        URI requestPath = request.getPath();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Matching route {} - {}", httpMethod, requestPath);
        }

        // find a matching route
        Optional<UriRouteMatch<Object>> routeMatch = router.find(httpMethod, requestPath)
                .filter((match) -> match.test(request))
                .findFirst();

        RouteMatch<Object> route;

        if (!routeMatch.isPresent()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No matching route found for URI {} and method {}", request.getUri(), httpMethod);
            }

            // if there is no route present try to locate a route that matches a different HTTP method
            Set<HttpMethod> existingRoutes = router
                    .findAny(request.getUri().toString())
                    .map(UriRouteMatch::getHttpMethod)
                    .collect(Collectors.toSet());

            if (!existingRoutes.isEmpty()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Method not allowed for URI {} and method {}", request.getUri(), httpMethod);
                }
                Optional<RouteMatch<Object>> statusRoute = router.route(HttpStatus.METHOD_NOT_ALLOWED);
                if (statusRoute.isPresent()) {
                    route = statusRoute.get();
                } else {
                    MutableHttpResponse<Object> defaultResponse = HttpResponse.notAllowed(existingRoutes);
                    emitDefaultResponse(ctx, request, defaultResponse);
                    return;
                }

            } else {
                Optional<RouteMatch<Object>> statusRoute = router.route(HttpStatus.NOT_FOUND);
                if (statusRoute.isPresent()) {
                    route = statusRoute.get();
                } else {
                    MutableHttpResponse<Object> res = HttpResponse.notFound();
                    emitDefaultResponse(ctx, request, res);
                    return;
                }
            }
        } else {
            route = routeMatch.get();
        }
        // Check that the route is an accepted content type
        MediaType contentType = request.getContentType().orElse(null);
        if (!route.accept(contentType)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Matched route is not a supported media type: {}", contentType);
            }
            Optional<RouteMatch<Object>> statusRoute = router.route(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            if (statusRoute.isPresent()) {
                route = statusRoute.get();
            } else {
                MutableHttpResponse<Object> res = HttpResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                emitDefaultResponse(ctx, request, res);
                return;
            }

        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Matched route {} - {} to controller {}", httpMethod, requestPath, route.getDeclaringType().getName());
        }
        // all ok proceed to try and execute the route
        handleRouteMatch(route, (NettyHttpRequest) request, ctx);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        NettyHttpRequest nettyHttpRequest = NettyHttpRequest.get(ctx);
        RouteMatch<Object> errorRoute = null;
        boolean hasRequest = nettyHttpRequest != null;
        if (cause instanceof UnsatisfiedRouteException) {
            errorRoute = router.route(HttpStatus.BAD_REQUEST).orElse(null);
        }
        if (errorRoute == null && hasRequest) {

            RouteMatch<?> originalRoute = nettyHttpRequest.getMatchedRoute();
            Class declaringType = originalRoute != null ? originalRoute.getDeclaringType() : null;
            errorRoute = (declaringType != null ? router.route(declaringType, cause) : router.route(cause)).orElse(null);
        }

        if (errorRoute != null && hasRequest) {
            errorRoute = requestArgumentSatisfier.fulfillArgumentRequirements(errorRoute, nettyHttpRequest);
            MediaType defaultResponseMediaType = errorRoute.getProduces().stream().findFirst().orElse(MediaType.APPLICATION_JSON_TYPE);
            if (errorRoute.isExecutable()) {
                try {
                    Object result = errorRoute.execute();
                    HttpResponse response = errorResultToResponse(result);

                    processResponse(ctx, nettyHttpRequest, response, defaultResponseMediaType, errorRoute);
                } catch (Throwable e) {
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Exception occurred executing error handler. Falling back to default error handling: " + e.getMessage(), e);
                    }
                    writeDefaultErrorResponse(ctx);
                }
            } else {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Unexpected error occurred: " + cause.getMessage(), cause);
                }
                writeDefaultErrorResponse(ctx);
            }
        } else {
            Optional<ExceptionHandler> exceptionHandler = beanLocator
                    .findBean(ExceptionHandler.class, Qualifiers.byTypeArguments(cause.getClass(), Object.class));

            if (hasRequest && exceptionHandler.isPresent()) {
                ExceptionHandler handler = exceptionHandler.get();
                MediaType defaultResponseMediaType = MediaType.fromType(exceptionHandler.getClass()).orElse(MediaType.APPLICATION_JSON_TYPE);
                try {
                    Object result = handler.handle(nettyHttpRequest, cause);
                    HttpResponse response = errorResultToResponse(result);
                    processResponse(ctx, nettyHttpRequest, response, defaultResponseMediaType, null);
                } catch (Throwable e) {
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Exception occurred executing error handler. Falling back to default error handling: " + e.getMessage(), e);
                    }
                    writeDefaultErrorResponse(ctx);
                }
            } else {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Unexpected error occurred: " + cause.getMessage(), cause);
                }

                writeDefaultErrorResponse(ctx);
            }
        }
    }


    private HttpResponse errorResultToResponse(Object result) {
        MutableHttpResponse<?> response;
        if (result == null) {
            response = HttpResponse.serverError();
        } else if (result instanceof HttpResponse) {
            response = (MutableHttpResponse) result;
        } else {
            response = HttpResponse.serverError()
                    .body(result);
            MediaType.fromType(result.getClass()).ifPresent(response::contentType);
        }
        return response;
    }

    private void handleRouteMatch(
            RouteMatch<Object> route,
            NettyHttpRequest<?> request,
            ChannelHandlerContext context) {
        // Set the matched route on the request
        request.setMatchedRoute(route);

        // try to fulfill the argument requirements of the route
        route = requestArgumentSatisfier.fulfillArgumentRequirements(route, request);

        // If it is not executable and the body is not required send back 400 - BAD REQUEST

        // decorate the execution of the route so that it runs an async executor
        request.setMatchedRoute(route);


        // The request body is required, so at this point we must have a StreamedHttpRequest
        io.netty.handler.codec.http.HttpRequest nativeRequest = request.getNativeRequest();
        if(!route.isExecutable() && HttpMethod.permitsRequestBody(request.getMethod()) && nativeRequest instanceof StreamedHttpRequest) {
            Optional<MediaType> contentType = request.getContentType();
            HttpContentProcessor<?> processor = contentType.flatMap(type ->
                    beanLocator.findBean(HttpContentSubscriberFactory.class,
                            new ConsumesMediaTypeQualifier<>(type))
            ).map(factory ->
                    factory.build(request)
            ).orElse(new DefaultHttpContentProcessor(request, serverConfiguration));

            processor.subscribe(buildSubscriber(request, context, route));
        } else {
            route = prepareRouteForExecution(route, request);
            route.execute();
        }
    }


    private Subscriber<Object> buildSubscriber(NettyHttpRequest request, ChannelHandlerContext context, RouteMatch<Object> finalRoute) {
        return new CompletionAwareSubscriber<Object>() {
            NettyPart currentPart;
            RouteMatch<Object> routeMatch = finalRoute;
            AtomicBoolean executed = new AtomicBoolean(false);

            @Override
            protected void doOnSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            protected void doOnNext(Object message) {
                boolean executed = this.executed.get();
                if (message instanceof ByteBufHolder) {
                    if (message instanceof HttpData) {
                        HttpData data = (HttpData) message;
                        String name = data.getName();
                        if (executed) {
                            if (currentPart != null) {
                                if (currentPart.getName().equals(name)) {
                                    FileUpload upload = (FileUpload) data;
                                    currentPart.onNext(upload);
                                    if (upload.isCompleted()) {
                                        currentPart.onComplete();
                                    }
                                } else {
                                    onComplete();
                                }
                            } else {
                                onComplete();
                            }
                        } else {
                            Optional<Argument<?>> requiredInput = routeMatch.getRequiredInput(name);

                            if (requiredInput.isPresent()) {
                                Object input = data;
                                if (data instanceof FileUpload) {
                                    Argument<?> argument = requiredInput.get();
                                    FileUpload fileUpload = (FileUpload) data;
                                    if (org.particleframework.http.multipart.FileUpload.class.isAssignableFrom(argument.getType())) {
                                        currentPart = createPart(fileUpload);
                                        input = currentPart;
                                    }
                                }
                                routeMatch = routeMatch.fulfill(Collections.singletonMap(name, input));
                            } else {
                                request.addContent(data);
                            }
                        }
                    } else {
                        request.addContent((ByteBufHolder) message);
                        if (!routeMatch.isExecutable() && message instanceof LastHttpContent) {
                            Optional<Argument<?>> bodyArgument = routeMatch.getBodyArgument();
                            if (bodyArgument.isPresent()) {
                                Argument<?> argument = bodyArgument.get();
                                String bodyArgumentName = argument.getName();
                                if (routeMatch.isRequiredInput(bodyArgumentName)) {
                                    Optional body = request.getBody();
                                    if (body.isPresent()) {
                                        routeMatch = routeMatch.fulfill(
                                                Collections.singletonMap(
                                                        bodyArgumentName,
                                                        body.get()
                                                )
                                        );
                                    }
                                }
                            }
                        }
                    }
                } else {
                    request.setBody(message);
                }


                if (!executed) {
                    if (routeMatch.isExecutable() || message instanceof LastHttpContent) {
                        // we have enough data to satisfy the route, continue
                        doOnComplete();
                    } else {
                        // the route is not yet executable, so keep going
                        subscription.request(1);
                    }
                }
            }

            private NettyPart createPart(FileUpload fileUpload) {
                return new NettyPart(
                        fileUpload,
                        serverConfiguration.getMultipart(),
                        ioExecutor,
                        subscription
                );
            }

            @Override
            protected void doOnError(Throwable t) {
                context.pipeline().fireExceptionCaught(t);
            }

            @Override
            protected void doOnComplete() {
                if (executed.compareAndSet(false, true)) {
                    try {
                        routeMatch = prepareRouteForExecution(routeMatch, request);
                        routeMatch.execute();
                    } catch (Exception e) {
                        context.pipeline().fireExceptionCaught(e);
                    }
                }
            }

        };
    }


    private RouteMatch<Object> prepareRouteForExecution(RouteMatch<Object> route, NettyHttpRequest<?> request) {
        ChannelHandlerContext context = request.getChannelHandlerContext();
        // Select the most appropriate Executor
        ExecutorService executor = executorSelector.select(route)
                .orElse(context.channel().eventLoop());

        route = route.decorate(finalRoute -> {
            MediaType defaultResponseMediaType = finalRoute
                    .getProduces()
                    .stream()
                    .findFirst()
                    .orElse(MediaType.APPLICATION_JSON_TYPE);

            Publisher<? extends HttpResponse<?>> finalPublisher;
            Publisher<MutableHttpResponse<?>> routePublisher = Publishers.fromCompletableFuture(() -> {
                CompletableFuture<MutableHttpResponse<?>> completableFuture = new CompletableFuture<>();
                executor.submit(() -> {

                    MutableHttpResponse<?> response;

                    try {
                        RouteMatch<Object> routeMatch = finalRoute;
                        if (!routeMatch.isExecutable()) {
                            routeMatch = requestArgumentSatisfier.fulfillArgumentRequirements(routeMatch, request);
                        }
                        Object result = routeMatch.execute();

                        if (result == null) {
                            response = NettyHttpResponse.getOr(request, HttpResponse.ok());
                        } else if (result instanceof HttpResponse) {
                            HttpStatus status = ((HttpResponse) result).getStatus();
                            if (status.getCode() >= 300) {
                                // handle re-mapping of errors
                                result = router.route(status)
                                        .map((match) -> requestArgumentSatisfier.fulfillArgumentRequirements(match, request))
                                        .filter(RouteMatch::isExecutable)
                                        .map(RouteMatch::execute)
                                        .orElse(result);
                            }
                            if (result instanceof MutableHttpResponse) {
                                response = (MutableHttpResponse<?>) result;
                            } else {
                                response = HttpResponse.status(status)
                                        .body(result);
                            }
                        } else {
                            response = HttpResponse.ok(result);
                        }

                        completableFuture.complete(response);

                    } catch (Throwable e) {
                        completableFuture.completeExceptionally(e);
                    }
                });
                return completableFuture;
            });

            finalPublisher = filterPublisher(request, routePublisher);

            finalPublisher.subscribe(new CompletionAwareSubscriber<HttpResponse<?>>() {
                @Override
                protected void doOnSubscribe(Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                protected void doOnNext(HttpResponse<?> message) {
                    processResponse(context, request, message, defaultResponseMediaType, finalRoute);
                }

                @Override
                protected void doOnError(Throwable t) {
                    context.pipeline().fireExceptionCaught(t);
                }

                @Override
                protected void doOnComplete() {
                    // no-op
                }
            });

            return null;
        });
        return route;
    }

    private Publisher<? extends HttpResponse<?>> filterPublisher(HttpRequest<?> request, Publisher<MutableHttpResponse<?>> routePublisher) {
        Publisher<? extends HttpResponse<?>> finalPublisher;
        List<HttpFilter> filters = new ArrayList<>( router.findFilters(request) );
        if(!filters.isEmpty()) {
            // make the action executor the last filter in the chain
            filters.add((HttpServerFilter) (req, chain) -> routePublisher);

            AtomicInteger integer = new AtomicInteger();
            int len = filters.size();
            HttpServerFilter.ServerFilterChain filterChain = new HttpServerFilter.ServerFilterChain() {
                @SuppressWarnings("unchecked")
                @Override
                public Publisher<MutableHttpResponse<?>> proceed(HttpRequest<?> request) {
                    int pos = integer.incrementAndGet();
                    if(pos > len) {
                        throw new IllegalStateException("The FilterChain.proceed(..) method should be invoked exactly once per filter execution. The method has instead been invoked multiple times by an erroneous filter definition.");
                    }
                    HttpFilter httpFilter = filters.get(pos);
                    return (Publisher<MutableHttpResponse<?>>) httpFilter.doFilter(request, this);
                }
            };
            HttpFilter httpFilter = filters.get(0);
            finalPublisher = httpFilter.doFilter(request, filterChain);
        }
        else {
            finalPublisher = routePublisher;
        }
        return finalPublisher;
    }

    private void processResponse(
            ChannelHandlerContext context,
            NettyHttpRequest<?> request,
            HttpResponse<?> response,
            MediaType defaultResponseMediaType,
            RouteMatch<Object> route) {
        Optional<?> optionalBody = response.getBody();
        FullHttpResponse nativeResponse = ((NettyHttpResponse) response).getNativeResponse();
        boolean isChunked = HttpUtil.isTransferEncodingChunked(nativeResponse);
        if (optionalBody.isPresent()) {
            // a response body is present so we need to process it
            Object body = optionalBody.get();
            Class<?> bodyType = body.getClass();


            MediaType responseType = response.getContentType()
                    .orElse(defaultResponseMediaType);

            Publisher<Object> publisher;
            MediaTypeCodec codec;
            if (Publishers.isPublisher(bodyType)) {
                // if the return type is a reactive type we need to subscribe to Publisher in order to emit
                // an appropriate response
                bodyType = resolveBodyType(route, bodyType);
                codec = resolveRouteCodec(bodyType, responseType);
                publisher = convertPublisher(body, bodyType);
            } else {
                // the return result is not a reactive type so build a publisher for the result that runs on the I/O scheduler
                if (body instanceof CompletableFuture) {
                    bodyType = resolveBodyType(route, bodyType);
                    codec = resolveRouteCodec(bodyType, responseType);

                    publisher = Publishers.fromCompletableFuture(() -> (CompletableFuture<Object>) body);
                } else {
                    codec = mediaTypeCodecRegistry.findCodec(
                            responseType, bodyType
                    ).orElse(new TextPlainCodec(serverConfiguration));
                    publisher = Publishers.fromCompletableFuture(() -> {
                        if (body instanceof byte[] || body instanceof ByteBuf) {
                            return CompletableFuture.completedFuture(body);
                        } else {
                            CompletableFuture<Object> future = new CompletableFuture<>();
                            ioExecutor.submit(() -> {
                                try {
                                    future.complete(codec.encode(body, new NettyByteBufferAllocator(context.alloc())).asNativeBuffer());
                                } catch (CodecException e) {
                                    future.completeExceptionally(e);
                                }
                            });
                            return future;
                        }
                    });
                }
            }


            if (isChunked) {
                // if the transfer encoding is chunked then create a com.typesafe.netty.http.StreamedHttpResponse
                // that will send the encoded data chunk by chunk

                // adapt the publisher to produce HTTP content
                writeHttpContentChunkByChunk(context, request, nativeResponse, responseType, codec, publisher);
            } else {
                // if the transfer encoding is not chunked then we must send a content length header so subscribe the
                // publisher, encode the result as a io.netty.handler.codec.http.FullHttpResponse
                boolean isSingle = Publishers.isSingle(publisher.getClass()) || HttpResponse.class.isAssignableFrom(bodyType);
                if (isSingle) {
                    publisher.subscribe(new CompletionAwareSubscriber<Object>() {
                        Subscription s;
                        Object message;

                        @Override
                        protected void doOnSubscribe(Subscription subscription) {
                            this.s = subscription;
                            this.s.request(1);
                        }

                        @Override
                        protected void doOnNext(Object message) {
                            this.message = message;
                        }

                        @Override
                        protected void doOnError(Throwable t) {
                            context.pipeline().fireExceptionCaught(t);
                        }

                        @Override
                        protected void doOnComplete() {
                            if (message != null) {
                                if (message instanceof HttpResponse) {
                                    HttpResponse<?> responseMessage = (HttpResponse<?>) this.message;
                                    Object body = responseMessage.getBody().orElse(null);
                                    MediaTypeCodec codecToUse = codec;
                                    if (body != null) {
                                        codecToUse = mediaTypeCodecRegistry.findCodec(
                                                responseType,
                                                body.getClass()
                                        ).orElse(new TextPlainCodec(serverConfiguration));
                                    }
                                    writeMessage(context, request, nativeResponse, body, codecToUse, responseType);
                                } else {
                                    writeMessage(context, request, nativeResponse, message, codec, responseType);
                                }
                            } else {
                                // no body returned so just write the Netty response as is
                                writeNettyResponse(context, request, nativeResponse);
                            }
                        }
                    });
                } else {
                    writeHttpContentChunkByChunk(context, request, nativeResponse, responseType, codec, publisher);
                }
            }
        } else {
            // no body returned so just write the Netty response as is
            writeNettyResponse(context, request, nativeResponse);
        }
    }

    @SuppressWarnings("unchecked")
    private Publisher<Object> convertPublisher(Object body, Class<?> bodyType) {
        return ConversionService.SHARED.convert(body, Publisher.class)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Reactive type: " + bodyType));
    }


    void writeDefaultErrorResponse(ChannelHandlerContext ctx) {
        ctx.channel()
                .writeAndFlush(HttpResponse.serverError())
                .addListener(ChannelFutureListener.CLOSE);
    }


    private MediaTypeCodec resolveRouteCodec(Class<?> bodyType, MediaType responseType) {
        MediaTypeCodec codec;
        codec = mediaTypeCodecRegistry.findCodec(
                responseType, bodyType
        ).orElse(new TextPlainCodec(serverConfiguration));
        return codec;
    }

    private Class<?> resolveBodyType(RouteMatch<Object> route, Class<?> bodyType) {
        if (route != null) {
            bodyType = route.getReturnType().getFirstTypeVariable().map(Argument::getType).orElse(null);
        }
        if (bodyType == null) {
            bodyType = Object.class;
        }
        return bodyType;
    }

    private void emitDefaultResponse(ChannelHandlerContext ctx, HttpRequest<?> request, MutableHttpResponse<Object> defaultResponse) {
        Publisher<MutableHttpResponse<?>> notAllowedResponse = Publishers.just(defaultResponse);
        notAllowedResponse  = (Publisher<MutableHttpResponse<?>>) filterPublisher(request, notAllowedResponse);
        notAllowedResponse.subscribe(new CompletionAwareSubscriber<MutableHttpResponse<?>>() {
            @Override
            protected void doOnSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            protected void doOnNext(MutableHttpResponse<?> message) {
                writeNettyResponse(ctx, request, ((NettyHttpResponse) message).getNativeResponse());
            }

            @Override
            protected void doOnError(Throwable t) {
                if(LOG.isErrorEnabled()) {
                    LOG.error("Unexpected error occurred: " + t.getMessage(), t);
                }
                writeNettyResponse(ctx, request, ((NettyHttpResponse) HttpResponse.serverError()).getNativeResponse());
            }

            @Override
            protected void doOnComplete() {

            }
        });
    }

    private void writeHttpContentChunkByChunk(
            ChannelHandlerContext context,
            NettyHttpRequest<?> request,
            FullHttpResponse nativeResponse,
            MediaType responseType,
            MediaTypeCodec codec,
            Publisher<Object> publisher) {
        Publisher<HttpContent> httpContentPublisher = Publishers.map(publisher, message -> {
            if (message instanceof ByteBuf) {
                return new DefaultHttpContent((ByteBuf) message);
            } else if (message instanceof HttpContent) {
                return (HttpContent) message;
            } else {
                ByteBuffer encoded = codec.encode(message, new NettyByteBufferAllocator(context.alloc()));
                return new DefaultHttpContent((ByteBuf) encoded.asNativeBuffer());
            }
        });

        if (responseType.equals(MediaType.TEXT_EVENT_STREAM_TYPE)) {

            httpContentPublisher = Publishers.onComplete(httpContentPublisher, () -> {
                CompletableFuture<Void> future = new CompletableFuture<>();
                if (request == null || !request.getHeaders().isKeepAlive()) {
                    context.pipeline()
                            .writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT))
                            .addListener(f -> {
                                if(f.isSuccess()) {
                                    future.complete(null);
                                }
                                else {
                                    future.completeExceptionally(f.cause());
                                }
                                    }
                            );
                }
                return future;
            });
        }

        DelegateStreamedHttpResponse streamedResponse = new DelegateStreamedHttpResponse(nativeResponse, httpContentPublisher);
        streamedResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        writeNettyResponse(
                context,
                request,
                streamedResponse
        );
    }

    private void writeMessage(
            ChannelHandlerContext context,
            NettyHttpRequest<?> request,
            FullHttpResponse nativeResponse,
            Object message,
            MediaTypeCodec codec,
            MediaType mediaType) {
        if (message != null) {

            ByteBuf byteBuf;

            if (message instanceof ByteBuf) {
                byteBuf = (ByteBuf) message;
            } else {
                byteBuf = (ByteBuf) codec.encode(message, new NettyByteBufferAllocator(context.alloc())).asNativeBuffer();
            }
            int len = byteBuf.readableBytes();
            FullHttpResponse newResponse = nativeResponse.replace(byteBuf);
            HttpHeaders headers = newResponse.headers();
            headers.add(HttpHeaderNames.CONTENT_TYPE, mediaType);
            headers.add(HttpHeaderNames.CONTENT_LENGTH, len);
            writeNettyResponse(context, request, newResponse);
        } else {
            writeNettyResponse(context, request, nativeResponse);
        }

    }

    private void writeNettyResponse(
            ChannelHandlerContext context,
            HttpRequest<?> request,
            io.netty.handler.codec.http.HttpResponse nettyResponse) {
        context.writeAndFlush(nettyResponse)
                .addListener((ChannelFuture future) -> {
                    if (!future.isSuccess()) {
                        Throwable cause = future.cause();
                        // swallow closed channel exception, nothing we can do about it if the client disconnects
                        if (!(cause instanceof ClosedChannelException)) {
                            if (LOG.isErrorEnabled()) {
                                LOG.error("Writing writing Netty response: " + cause.getMessage(), cause);
                            }
                            Channel channel = context.channel();
                            if (channel.isWritable()) {
                                context.pipeline().fireExceptionCaught(cause);
                            } else {
                                channel.close();
                            }
                        }
                    } else if (!HttpUtil.isKeepAlive(((NettyHttpRequest) request).getNativeRequest()) || nettyResponse.status().code() >= 300) {
                        future.channel().close();
                    }
                });
    }


}