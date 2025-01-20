/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslEngine;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http3SettingsSpec;
import reactor.netty.tcp.SslProvider;
import reactor.util.annotation.Nullable;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.netty.incubator.codec.http3.Http3.newQuicClientCodecBuilder;

final class Http3ChannelInitializer extends ChannelInitializer<Channel> {

	final Http3SettingsSpec           http3Settings;
	final ChannelHandler              loggingHandler;
	final ConnectionObserver          obs;
	final ChannelOperations.OnSetup   opsFactory;
	final ChannelInitializer<Channel> quicChannelInitializer;
	final SocketAddress               remoteAddress;
	final SslProvider                 sslProvider;

	Http3ChannelInitializer(HttpClientConfig config, ChannelInitializer<Channel> quicChannelInitializer, ConnectionObserver obs,
			@Nullable SocketAddress remoteAddress) {
		this.http3Settings = config.http3SettingsSpec();
		this.loggingHandler = config.loggingHandler();
		this.obs = obs;
		this.opsFactory = config.channelOperationsProvider();
		this.quicChannelInitializer = quicChannelInitializer;
		this.remoteAddress = remoteAddress;
		this.sslProvider = config.sslProvider;
	}

	@Override
	protected void initChannel(Channel channel) {
		QuicClientCodecBuilder quicClientCodecBuilder = newQuicClientCodecBuilder();

		quicClientCodecBuilder.sslEngineProvider(ch -> {
			QuicSslContext quicSslContext;
			if (sslProvider.getSslContext() instanceof QuicSslContext) {
				quicSslContext = (QuicSslContext) sslProvider.getSslContext();
			}
			else {
				throw new IllegalArgumentException("The configured SslContext is not QuicSslContext");
			}

			QuicSslEngine engine;
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress sniInfo = (InetSocketAddress) remoteAddress;
				if (sslProvider.getServerNames() != null && !sslProvider.getServerNames().isEmpty()) {
					SNIServerName serverName = sslProvider.getServerNames().get(0);
					String serverNameStr = serverName instanceof SNIHostName ? ((SNIHostName) serverName).getAsciiName() :
							new String(serverName.getEncoded(), StandardCharsets.US_ASCII);
					engine = quicSslContext.newEngine(ch.alloc(), serverNameStr, sniInfo.getPort());
				}
				else {
					engine = quicSslContext.newEngine(ch.alloc(), sniInfo.getHostString(), sniInfo.getPort());
				}
			}
			else {
				engine = quicSslContext.newEngine(ch.alloc());
			}

			return engine;
		});

		if (http3Settings != null) {
			quicClientCodecBuilder.initialMaxData(http3Settings.maxData())
			                      .initialMaxStreamDataBidirectionalLocal(http3Settings.maxStreamDataBidirectionalLocal())
			                      .initialMaxStreamDataBidirectionalRemote(http3Settings.maxStreamDataBidirectionalRemote())
			                      .initialMaxStreamsBidirectional(http3Settings.maxStreamsBidirectional());

			if (http3Settings.idleTimeout() != null) {
				quicClientCodecBuilder.maxIdleTimeout(http3Settings.idleTimeout().toMillis(), TimeUnit.MILLISECONDS);
			}
		}

		if (loggingHandler != null) {
			channel.pipeline().addLast(NettyPipeline.LoggingHandler, loggingHandler);
		}
		channel.pipeline().addLast(quicClientCodecBuilder.build());
		channel.pipeline().addLast(NettyPipeline.HttpTrafficHandler, new HttpTrafficHandler(obs, quicChannelInitializer));

		ChannelOperations.addReactiveBridge(channel, opsFactory, obs);

		channel.config().setAutoRead(true);
	}

	static class HttpTrafficHandler extends ChannelInboundHandlerAdapter {
		final ConnectionObserver listener;
		final ChannelHandler quicChannelInitializer;

		HttpTrafficHandler(ConnectionObserver listener, ChannelHandler quicChannelInitializer) {
			this.listener = listener;
			this.quicChannelInitializer = quicChannelInitializer;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			if (ctx.channel().isActive()) {
				Connection c = Connection.from(ctx.channel());
				listener.onStateChange(c, ConnectionObserver.State.CONNECTED);
				listener.onStateChange(c, ConnectionObserver.State.CONFIGURED);
			}
		}
	}
}