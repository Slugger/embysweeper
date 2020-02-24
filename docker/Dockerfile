FROM openjdk:8-alpine

ARG USRGRP=embyapi
ARG USRGRPID=9797
ARG APPROOT=/opt/embysweeper-shadow

COPY root/ /opt/

RUN apk --no-cache add bash && \
    addgroup -g $USRGRPID $USRGRP && \
    adduser -D -u $USRGRPID -G $USRGRP $USRGRP && \
    chmod 755 $APPROOT/bin/embysweeper && \
    ln -s $APPROOT/bin/embysweeper /usr/local/bin/embysweeper

USER $USRGRP:$USRGRP
ENTRYPOINT ["/usr/local/bin/embysweeper"]