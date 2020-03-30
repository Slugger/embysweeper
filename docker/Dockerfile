FROM openjdk:8-alpine

ARG USRGRP=embyapi
ARG USRGRPID=9797

COPY root/ /opt/

RUN apk --no-cache add bash && \
    addgroup -g $USRGRPID $USRGRP && \
    adduser -D -u $USRGRPID -G $USRGRP $USRGRP && \
    unzip /opt/*.zip && \
    rm -f /opt/*.zip && \
    find /opt/ -type f -name embysweeper -exec chmod 755 {} \; && \
    find /opt/ -type f -name embysweeper -exec ln -s {} /usr/local/bin/embysweeper \;

USER $USRGRP:$USRGRP
ENTRYPOINT ["/usr/local/bin/embysweeper"]