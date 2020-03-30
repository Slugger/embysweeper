FROM openjdk:8-alpine

ARG USRGRP=embyapi
ARG USRGRPID=9797

COPY root/ /opt/

RUN apk --no-cache add bash && \
    addgroup -g $USRGRPID $USRGRP && \
    adduser -D -u $USRGRPID -G $USRGRP $USRGRP && \
    unzip /opt/*.zip && \
    rm -f /opt/*.zip && \
    chmod 755 /opt/embysweeper*/bin/embysweeper && \
    ln -s /opt/embysweeper*/bin/embysweeper /usr/local/bin/embysweeper

USER $USRGRP:$USRGRP
ENTRYPOINT ["/usr/local/bin/embysweeper"]