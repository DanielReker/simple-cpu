INPUT
MOV [n], A

loop:

MOV A, [n]
JZ end
DEC A
MOV [n], A

MOV A, [right]
MOV B, A
MOV A, [left]
ADD A, B
MOV [right], A
SWAP A, B
MOV [left], A

JMP loop

end:
MOV A, [left]
OUTPUT
HALT

left: db 0
right: db 1

n: db 0