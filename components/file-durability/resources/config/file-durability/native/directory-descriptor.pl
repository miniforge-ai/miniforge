# Title: Miniforge.ai
# Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
# Licensed under the Apache License, Version 2.0.
use strict;
use warnings;
use Fcntl qw(O_RDONLY O_NOFOLLOW S_ISDIR);
use constant REFUSED => 64;

@ARGV == 1 or exit REFUSED;
sysopen(my $pin, $ARGV[0], O_RDONLY | O_NOFOLLOW) or exit REFUSED;
my ($device, $inode, $mode) = stat($pin);
defined($mode) && S_ISDIR($mode) or exit REFUSED;
print STDOUT "$device $inode\n" or exit REFUSED;
close(STDOUT) or exit REFUSED;
# EOF ties the descriptor to the host's pipe lifetime, including host death.
my $received = sysread(STDIN, my $byte, 1);
close($pin) or exit REFUSED;
exit(defined($received) && $received == 0 ? 0 : REFUSED);
