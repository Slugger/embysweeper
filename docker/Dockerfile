FROM openjdk:8-alpine

ARG USRGRP=embyapi
ARG USRGRPID=9797
ARG APPROOT=/opt/embysweeper-shadow

COPY root/ /opt/

RUN addgroup -g $USRGRPID $USRGRP && \
    adduser -u $USRGRPID -G $USRGRP $USRGRP && \
    chmod 755 $APPROOT/bin/embysweeper

ENTRYPOINT ["$APPROOT/bin/embysweeper"]